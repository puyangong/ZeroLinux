package com.zerolinux.app.container

/**
 * 首次初始化：换源后安装软件栈。
 *
 * 脚本落盘到 /opt/zerolinux/bootstrap.sh，由 ContainerOps 用 PRoot 跑。
 * 每步完成写入 /opt/zerolinux/.state，支持断点续跑；进度用 [ZL:STEP:n] 标记回传。
 */
object BootstrapScript {

    const val MARK_STEP = "[ZL:STEP:"
    const val MARK_PROGRESS = "[ZL:PROGRESS:"
    const val MARK_DONE = "[ZL:DONE:"
    const val MARK_FAIL = "[ZL:FAIL:"
    const val MARK_VERIFY = "[ZL:VERIFY:"
    const val MARK_FINISHED = "[ZL:FINISHED]"

    /** 可选组件失败标记：只写 warning，不影响 `initialized`。 */
    const val MARK_OPTIONAL_FAILED = "[ZL:OPTIONAL_FAILED]"

    fun script(distro: DistroId, plan: MirrorConfigurator.MirrorPlan): String {
        return if (distro == DistroId.ALPINE) alpine(plan).trimIndent().replace("@@", "$")
        else debianFamily(plan).trimIndent().replace("@@", "$")
    }

    /**
     * 必需步骤：缺一即 `initialized=false`，必须让用户重试。
     *
     * Alpine：1 = 换源 + apk 索引，2 = 必需包（bash/curl/git/openssh/python3/py3-pip/xz）。
     * Debian 系：1 = apt 索引（含换源回滚），2 = 基础包。
     */
    fun requiredSteps(distro: DistroId): Set<Int> = setOf(1, 2, 3, 4, 5)

    /**
     * 现在 Node.js / Rust / Go 都是必选组件：失败会阻塞 initialized=true。
     * 保留这个函数是为了让 UI 仍能按 3/4/5 展示步骤标签。
     */
    fun optionalSteps(distro: DistroId): Set<Int> = emptySet()

    /** 每个步骤的人话名字，写进 lastError / 诊断日志。 */
    fun stepLabel(distro: DistroId, step: Int): String = when {
        step == 1 -> "mirror+index"
        step == 2 -> "required packages"
        distro == DistroId.ALPINE -> when (step) {
            3 -> "node"
            4 -> "go"
            5 -> "rust"
            else -> "step $step"
        }
        else -> when (step) {
            3 -> "node"
            4 -> "rust"
            5 -> "go"
            else -> "step $step"
        }
    }

    /** 从 `[ZL:FAIL:<step>:<reason>]` 里解析失败的步骤号。 */
    fun failedSteps(output: String): Set<Int> =
        FAIL_MARK.findAll(output).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    /** 从 `[ZL:OPTIONAL_FAILED] tag=xxx` 里解析可选组件名。 */
    fun optionalFailedTags(output: String): List<String> =
        OPTIONAL_MARK.findAll(output)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private val FAIL_MARK = Regex("""\[ZL:FAIL:(\d+):""")
    private val OPTIONAL_MARK = Regex("""\[ZL:OPTIONAL_FAILED\]\s*tag=(\S+)""")

    /**
     * 步骤总数 = 必需 ∪ 可选的最大步骤号（当前为 5）。
     * 由集合推导而不是写死 5，`IT: total=` 与脚本实际步数不可能再对不上。
     */
    fun totalSteps(distro: DistroId): Int =
        (requiredSteps(distro) + optionalSteps(distro)).maxOrNull() ?: 5

    private fun header(): String = """
        #!/bin/sh
        # ZeroLinux bootstrap
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:@@PATH
        mkdir -p /opt/zerolinux
        STATE=/opt/zerolinux/.state
        touch @@STATE
        . /etc/profile.d/zerolinux-mirror.sh 2>/dev/null
        [ -f /opt/zerolinux/apt-mirror-lib.sh ] && . /opt/zerolinux/apt-mirror-lib.sh

        has() { grep -qx "@@1" "@@STATE" 2>/dev/null; }
        ok() { echo "@@1" >> "@@STATE"; echo "[ZL:DONE:@@1]"; }
        bad() { echo "[ZL:FAIL:@@1:@@2]"; }
        pct() { echo "[ZL:PROGRESS:@@1:@@2]"; }

        # 下载一个文件：断线后删除当前文件、从 0 重下；最多重连 3 次，期间每秒回报百分比。
        zl_fetch() {
          _url="@@1"
          _out="@@2"
          _step="@@3"
          _try=0
          _total=@@(curl -fsIL --max-time 30 "@@_url" 2>/dev/null | awk 'tolower(@@1)=="content-length:"{v=@@2} END{print v+0}')
          while [ "@@_try" -lt 3 ]; do
            _try=@@(( _try + 1 ))
            # 不续传旧分片：每次断线重连都从 0 重新下载这个文件。
            rm -f "@@_out"
            pct "@@_step" 0
            curl -fsL --connect-timeout 20 --speed-time 30 --speed-limit 1024 --retry 2 --retry-delay 2 -o "@@_out" "@@_url" &
            _pid=@@!
            while kill -0 "@@_pid" 2>/dev/null; do
              _size=@@(wc -c < "@@_out" 2>/dev/null || echo 0)
              if [ "@@_total" -gt 0 ]; then
                _p=@@(( _size * 100 / _total ))
                [ "@@_p" -gt 99 ] && _p=99
                pct "@@_step" "@@_p"
              fi
              sleep 1
            done
            wait "@@_pid"
            _rc=@@?
            _size=@@(wc -c < "@@_out" 2>/dev/null || echo 0)
            if [ "@@_rc" -eq 0 ] || { [ "@@_total" -gt 0 ] && [ "@@_size" -ge "@@_total" ]; }; then
              pct "@@_step" 100
              return 0
            fi
            sleep 2
          done
          return 1
        }
    """.trimIndent()

    private fun debianFamily(m: MirrorConfigurator.MirrorPlan): String {
        // rustup / go 的候选顺序由宿主机测速决定，失败自动回退下一项。
        val rustupUrls = m.rustupInit.joinToString(" ")
        val rustupMirrors = m.rustup.joinToString(" ") { "${it.dist}|${it.update}" }
        val goVersionUrls = m.goVersion.joinToString(" ")
        val goBases = m.go.joinToString(" ")

        return header() + """
        GO_VERSION_URLS='${goVersionUrls}'
        GO_BASES='${goBases}'
        export DEBIAN_FRONTEND=noninteractive

        if ! has 1; then
          echo "[ZL:STEP:1]"
          pct 1 0
          # 每个镜像 apt 自身重试 3 次；失败后 zl_apt_next 切换下一个镜像，
          # 轮完再回到第一个，最多循环 8 次。
          _cycle=0
          while [ "@@_cycle" -lt 8 ] && ! has 1; do
            _cycle=@@(( _cycle + 1 ))
            if zl_apt_update; then
              pct 1 100
              ok 1
              break
            fi
            tail -10 /tmp/zl-apt-update.log 2>/dev/null
            echo "[ZL:MIRROR:apt-switch] cycle=@@_cycle"
            zl_apt_next || break
          done
          has 1 || bad 1 "apt update"
        fi

        if ! has 2; then
          echo "[ZL:STEP:2]"
          pct 2 0
          pct 2 20
          ZL_CURRENT_STEP=2
          if zl_apt_install \
              bash ncurses-base curl ca-certificates git openssh-client openssh-server \
              python3 python3-pip python3-venv build-essential unzip xz-utils; then
            [ -e /usr/local/bin/python ] || ln -sf /usr/bin/python3 /usr/local/bin/python
            # 清掉 deb 包缓存与索引，能省 150-300MB；索引保留也没用，后面不再 apt update
            apt-get clean >/dev/null 2>&1
            rm -rf /var/lib/apt/lists/* >/dev/null 2>&1
            rm -rf /var/cache/apt/archives/*.deb >/dev/null 2>&1
            pct 2 100
            ok 2
          else
            tail -20 /tmp/zl-apt-install.log 2>/dev/null
            bad 2 "base packages"
          fi
        fi

        if ! has 3; then
          echo "[ZL:STEP:3]"
          pct 3 0
          NODE_BASES='${m.node.joinToString(" ")}'
          case "@@(uname -m)" in
            aarch64|arm64) NARCH=arm64 ;;
            armv7l|armv7|armv8l) NARCH=armv7l ;;
            x86_64|amd64) NARCH=x64 ;;
            *) NARCH=x64 ;;
          esac
          NODE_OK=0
          _cycle=0
          while [ "@@_cycle" -lt 2 ] && [ "@@NODE_OK" -eq 0 ]; do
            _cycle=@@(( _cycle + 1 ))
            for NB in @@NODE_BASES; do
              rm -f /tmp/zl-node-shasums.txt
              if ! zl_fetch "@@NB/latest/SHASUMS256.txt" /tmp/zl-node-shasums.txt 3; then
                continue
              fi
              NFILE=@@(grep "linux-@@NARCH.tar.xz" /tmp/zl-node-shasums.txt 2>/dev/null | awk '{print @@2}' | head -1)
              [ -n "@@NFILE" ] || continue
              if zl_fetch "@@NB/latest/@@NFILE" /tmp/zl-node.tar.xz 3; then
                if xz -dc /tmp/zl-node.tar.xz | tar -xf - -C /usr/local --strip-components=1 \
                   && ln -sf /usr/local/bin/node /usr/bin/node \
                   && ln -sf /usr/local/bin/npm /usr/bin/npm \
                   && ln -sf /usr/local/bin/npx /usr/bin/npx; then
                  NODE_OK=1
                  break
                fi
                rm -f /tmp/zl-node.tar.xz
              fi
            done
            [ "@@NODE_OK" -eq 0 ] && sleep 2
          done
          if [ "@@NODE_OK" -eq 1 ]; then
            pct 3 100
            ok 3
            # AI 模块：安装 OpenCode v2 CLI。npm 自身重试 3 次；
            # 失败后切换 registry；仍失败不阻断容器初始化。
            NPM_REGISTRIES='${(listOf(m.npmRegistry) + m.npmRegistryAlt).joinToString(" ")}'
            npm config set fetch-retries 3 >/dev/null 2>&1
            npm config set fetch-retry-mintimeout 2000 >/dev/null 2>&1
            npm config set fetch-retry-maxtimeout 10000 >/dev/null 2>&1
            npm config set fetch-timeout 60000 >/dev/null 2>&1
            _ncycle=0
            while [ "@@_ncycle" -lt 2 ]; do
              _ncycle=@@(( _ncycle + 1 ))
              for reg in @@NPM_REGISTRIES; do
                npm config set registry "@@reg" >/dev/null 2>&1
                if timeout 300 npm install -g @opencode/cli > /tmp/zl-opencode-npm.log 2>&1; then
                  _ncycle=9
                  break
                fi
              done
              [ "@@_ncycle" -lt 9 ] && sleep 2
            done
          else
            bad 3 "install node"
          fi
          rm -f /tmp/zl-node.tar.xz
        fi

        if ! has 4; then
          echo "[ZL:STEP:4]"
          pct 4 0
          # rustup 引导脚本：先 rsproxy 镜像，再官方；dist 镜像按宿主机实测吞吐排序。
          ZL_RUSTUP_URLS='${rustupUrls}'
          ZL_RUSTUP_MIRRORS='${rustupMirrors}'
          zl_rustup_mirror() {
            _dist=@@{1%%|*}
            _upd=@@{1#*|}
            export RUSTUP_DIST_SERVER="@@_dist"
            export RUSTUP_UPDATE_ROOT="@@_upd"
            export RUSTUP_MAX_RETRIES=3
            echo "[ZL:RUST:mirror] @@_dist"
            : > /tmp/zl-rustup.log
            timeout 1200 sh /tmp/zl-rustup-init.sh -y --profile minimal --default-toolchain stable --no-modify-path >> /tmp/zl-rustup.log 2>&1 &
            _rpid=@@!
            _last=0
            _idle=0
            _el=0
            _maxp=35
            _tick=0
            while kill -0 "@@_rpid" 2>/dev/null; do
              sleep 3
              _el=@@(( _el + 3 ))
              _tick=@@(( _tick + 1 ))
              _kb=@@(du -sk /root/.rustup/downloads /root/.rustup/tmp 2>/dev/null | awk '{s+=@@1} END {print s+0}')
              [ -n "@@_kb" ] || _kb=0
              _p=@@(( 35 + _kb * 55 / 112640 ))
              [ "@@_p" -gt 90 ] && _p=90
              [ "@@_p" -lt "@@_maxp" ] && _p=@@_maxp
              _maxp=@@_p
              pct 4 "@@_p"
              [ "@@(( _tick % 5 ))" -eq 0 ] && echo "[ZL:RUST:progress] kb=@@{_kb} elapsed=@@{_el}s"
              if [ "@@_kb" -gt "@@_last" ]; then
                _last=@@_kb
                _idle=0
              else
                _idle=@@(( _idle + 3 ))
              fi
              if [ "@@_idle" -ge 120 ]; then
                echo "[ZL:RUST:stalled] idle=@@{_idle}s mirror=@@_dist"
                kill "@@_rpid" 2>/dev/null
                break
              fi
            done
            wait "@@_rpid" 2>/dev/null
            [ "@@?" -eq 0 ] && [ -x /root/.cargo/bin/rustc ] && return 0
            tail -3 /tmp/zl-rustup.log 2>/dev/null
            return 1
          }
          zl_rustup() {
            for iu in @@ZL_RUSTUP_URLS; do
              rm -f /tmp/zl-rustup-init.sh
              if zl_fetch "@@iu" /tmp/zl-rustup-init.sh 4 && [ -s /tmp/zl-rustup-init.sh ]; then
                break
              fi
            done
            [ -s /tmp/zl-rustup-init.sh ] || return 1
            for m in @@ZL_RUSTUP_MIRRORS; do
              zl_rustup_mirror "@@m" && return 0
            done
            return 1
          }
          ZL_RUST_OK=0
          _cycle=0
          while [ "@@_cycle" -lt 2 ] && [ "@@ZL_RUST_OK" -ne 1 ]; do
            _cycle=@@(( _cycle + 1 ))
            if zl_rustup; then
              ZL_RUST_OK=1
            else
              sleep 2
            fi
          done
          if [ "@@ZL_RUST_OK" -eq 1 ]; then
            ln -sf /root/.cargo/bin/rustc /usr/local/bin/rustc 2>/dev/null
            ln -sf /root/.cargo/bin/cargo /usr/local/bin/cargo 2>/dev/null
            pct 4 100
            ok 4
          else
            # rustup 镜像全失败时，退回发行版官方仓库的 rustc/cargo。
            pct 4 90
            ZL_CURRENT_STEP=4
            if command -v zl_apt_install >/dev/null 2>&1 && zl_apt_install rustc cargo; then
              pct 4 100
              ok 4
            else
              bad 4 "rustup+apt"
            fi
          fi
          rm -f /tmp/zl-rustup-init.sh /tmp/zl-rustup.log
        fi

        if ! has 5; then
          echo "[ZL:STEP:5]"
          pct 5 0
          case "@@(uname -m)" in
            aarch64|arm64) GARCH=arm64 ;;
            armv7l|armv7|armv8l) GARCH=armv6l ;;
            x86_64|amd64) GARCH=amd64 ;;
            *) GARCH=arm64 ;;
          esac
          # 版本探测：国内先 golang.google.cn，失败再退 go.dev。
          GV=
          for u in @@GO_VERSION_URLS; do
            _gv=@@(curl -fsSL --max-time 30 "@@u" 2>/dev/null | head -1)
            if [ -n "@@_gv" ]; then GV="@@_gv"; break; fi
          done
          [ -z "@@GV" ] && GV=go1.23.0
          ZL_GO_OK=0
          _cycle=0
          # tarball base：国内 aliyun 优先（m.goBase 已是国内地址），官方 dl 兜底。
          while [ "@@_cycle" -lt 2 ] && [ "@@ZL_GO_OK" -ne 1 ]; do
            _cycle=@@(( _cycle + 1 ))
            for b in @@GO_BASES; do
              if zl_fetch "@@b/@@GV.linux-@@GARCH.tar.gz" /tmp/zl-go.tgz 5; then
                if rm -rf /usr/local/go && tar -C /usr/local -xzf /tmp/zl-go.tgz; then
                  ZL_GO_OK=1
                  break
                fi
                rm -f /tmp/zl-go.tgz
              fi
            done
            [ "@@ZL_GO_OK" -ne 1 ] && sleep 2
          done
          if [ "@@ZL_GO_OK" -ne 1 ]; then
            # tarball 镜像全失败时，退回发行版官方仓库的 golang-go/golang。
            pct 5 90
            ZL_CURRENT_STEP=5
            if command -v zl_apt_install >/dev/null 2>&1; then
              zl_apt_install golang-go || zl_apt_install golang || true
            fi
          fi
          if [ ! -x /usr/bin/go ] && [ ! -x /usr/local/go/bin/go ]; then
            bad 5 "go"
          else
            [ -x /usr/local/go/bin/go ] && ln -sf /usr/local/go/bin/go /usr/local/bin/go
            [ -x /usr/local/go/bin/gofmt ] && ln -sf /usr/local/go/bin/gofmt /usr/local/bin/gofmt
            pct 5 100
            ok 5
          fi
          rm -f /tmp/zl-go.tgz
        fi

        echo "[ZL:FINISHED]"
    """
    }

    /**
     * Alpine：apk 的失败判定不能靠 grep "unavailable"——源不可达时 apk 打的是
     * `WARNING: Ignoring APKINDEX...` 或 `temporary error`，退出码还可能是 0。
     * 因此统一以「索引文件是否真的落地」为准，并且每条 apk 命令都套 `timeout`，
     * 重试上限 1 次。否则国内源不可用 + 30KB/s 的链路会永远停在 bootstrap:2/5。
     */
    private fun alpine(m: MirrorConfigurator.MirrorPlan): String = header() + """
REL=@@(cut -d. -f1,2 /etc/alpine-release 2>/dev/null)
[ -z "@@REL" ] && REL=3.21
# 换源结果（候选列表 + 当前源 + 切换函数）由 MirrorConfigurator 落到这里。
if [ -f /etc/apk/zl-mirror-lib.sh ]; then
  . /etc/apk/zl-mirror-lib.sh
else
  echo "[ZL:MIRROR:lib] missing, use configured base"
  ZL_ALPINE_REL="@@REL"
  ZL_ALPINE_MIRRORS='${m.alpineBase}'
  ZL_ALPINE_CURRENT='${m.alpineBase}'
  zl_write_repos() {
    printf '%s\n' "@@1/v@@ZL_ALPINE_REL/main" "@@1/v@@ZL_ALPINE_REL/community" > /etc/apk/repositories
  }
  zl_next_mirror() { return 1; }
fi
# 换源脚本可能没跑成功：这里再按候选结果落一次 repositories，幂等。
zl_write_repos "@@ZL_ALPINE_CURRENT"
have_index() { ls /var/cache/apk/APKINDEX.* >/dev/null 2>&1; }
zl_now() {
  _t=@@(date +%s 2>/dev/null)
  case "@@_t" in ''|*[!0-9]*) echo 0 ;; *) echo "@@_t" ;; esac
}
# apk 日志里的真实进度：(37/49) Installing ...
# 输出 "37 49"；没有进度行时输出空。
zl_apk_progress() {
  sed -n 's/.*(\([0-9][0-9]*\)\/\([0-9][0-9]*\)).*/\1 \2/p' /tmp/zl-apk-add.log 2>/dev/null | tail -1
}
# apk update 全程只跑一次（换源重试才补一次），索引真的落地才算成功：
# apk 对不可达源也常返回 0。
zl_apk_update() {
  rm -f /var/cache/apk/APKINDEX.* 2>/dev/null
  timeout 180 apk update > /tmp/zl-apk-update.log 2>&1 && have_index
}
# zl_install <超时秒> <标签> <包...>
# 每个镜像最多 3 次；三次都失败才换镜像，最多 8 个镜像循环。
# 进度按 apk 日志的 (done/total) 计算；30 秒日志没有增长才判定卡死。
zl_install() {
  _limit="@@1"
  _tag="@@2"
  shift 2
  _cycle=0
  while [ "@@_cycle" -lt 8 ]; do
    _cycle=@@(( _cycle + 1 ))
    _attempt=0
    # 每个镜像连续重试 3 次；3 次都失败才切换下一个镜像。
    while [ "@@_attempt" -lt 3 ]; do
      _attempt=@@(( _attempt + 1 ))
      _t0=@@(zl_now)
      _last_size=0
      _last_change=@@(zl_now)
      _switched=0
      echo "[ZL:BATCH:@@_tag:start] cycle=@@_cycle attempt=@@_attempt limit=@@{_limit}s mirror=@@ZL_ALPINE_CURRENT"
      # 断线重连从 0 重下：只清未完成分片，不动已下载/已安装的包。
      rm -rf /var/cache/apk/partial/* 2>/dev/null
      apk add "@@@" > /tmp/zl-apk-add.log 2>&1 &
      _pid=@@!
      while kill -0 "@@_pid" 2>/dev/null; do
        sleep 3
        _el=@@(( @@(zl_now) - _t0 ))
        [ "@@_el" -gt 0 ] || _el=1
        _size=@@(wc -c < /tmp/zl-apk-add.log 2>/dev/null || echo 0)
        if [ "@@_size" -gt "@@_last_size" ]; then
          _last_size="@@_size"
          _last_change=@@(zl_now)
        fi
        _prog=@@(zl_apk_progress)
        _done=@@(echo "@@_prog" | awk '{print @@1 + 0}')
        _total=@@(echo "@@_prog" | awk '{print @@2 + 0}')
        if [ "@@_total" -gt 0 ]; then
          _p=@@(( _done * 100 / _total ))
          [ "@@_p" -gt 99 ] && _p=99
        else
          _p=@@(( _el * 100 / _limit ))
          [ "@@_p" -gt 95 ] && _p=95
        fi
        [ -n "@@ZL_CURRENT_STEP" ] && pct "@@ZL_CURRENT_STEP" "@@_p"
        echo "[ZL:BATCH:@@_tag] elapsed=@@{_el}s size=@@{_size} progress=@@{_p}%"
        _idle=@@(( @@(zl_now) - _last_change ))
        if [ "@@_idle" -ge 30 ]; then
          echo "[ZL:MIRROR:stalled] tag=@@_tag idle=@@{_idle}s"
          kill "@@_pid" 2>/dev/null
          _switched=1
          break
        fi
        if [ "@@_el" -ge "@@_limit" ]; then
          echo "[ZL:BATCH:@@_tag:timeout] elapsed=@@{_el}s"
          kill "@@_pid" 2>/dev/null
          break
        fi
      done
      wait "@@_pid" 2>/dev/null
      _rc=@@?
      if [ "@@_rc" -eq 0 ]; then
        echo "[ZL:BATCH:@@_tag:ok] cycle=@@_cycle attempt=@@_attempt elapsed=@@(( @@(zl_now) - _t0 ))s"
        return 0
      fi
      tail -3 /tmp/zl-apk-add.log 2>/dev/null
      [ "@@_switched" -eq 1 ] && break
      sleep 2
    done
    echo "[ZL:MIRROR:switch] tag=@@_tag cycle=@@_cycle mirror=@@ZL_ALPINE_CURRENT"
    zl_next_mirror || return 1
    zl_apk_update || true
  done
  return 1
}

if ! has 1; then
  echo "[ZL:STEP:1]"
  pct 1 0
  _t0=@@(zl_now)
  if zl_apk_update; then
    echo "[ZL:BATCH:index:ok] elapsed=@@(( @@(zl_now) - _t0 ))s mirror=@@ZL_ALPINE_CURRENT"
    pct 1 100
    ok 1
  else
    echo "[ZL:MIRROR:rollback]"
    zl_next_mirror >/dev/null 2>&1 || true
    if zl_apk_update; then
      echo "[ZL:BATCH:index:ok] retry elapsed=@@(( @@(zl_now) - _t0 ))s mirror=@@ZL_ALPINE_CURRENT"
      pct 1 100
      ok 1
    else
      tail -20 /tmp/zl-apk-update.log
      bad 1 "apk update"
    fi
  fi
fi

# 必需包：一批装完，容器尽快可用（bash/curl/git/python3 就能做大部分操作）。
if ! has 2; then
  echo "[ZL:STEP:2]"
  pct 2 0
  _t0=@@(zl_now)
  # 首跑中断会留下 apk 的脏状态；先 fix 再装，失败重试一次。
  apk fix >/tmp/zl-apk-fix.log 2>&1 || true
  ZL_CURRENT_STEP=2
  if zl_install 1200 base bash curl ca-certificates openssl git openssh python3 py3-pip xz; then
    [ -e /usr/local/bin/python ] || ln -sf /usr/bin/python3 /usr/local/bin/python
    echo "[ZL:BATCH:base:done] elapsed=@@(( @@(zl_now) - _t0 ))s"
    pct 2 100
    ok 2
  elif zl_install 1200 base bash curl ca-certificates openssl git openssh python3 py3-pip xz; then
    [ -e /usr/local/bin/python ] || ln -sf /usr/bin/python3 /usr/local/bin/python
    echo "[ZL:BATCH:base:done] elapsed=@@(( @@(zl_now) - _t0 ))s"
    pct 2 100
    ok 2
  else
    bad 2 "apk add base"
  fi
fi

# 可选大包：允许失败，不阻断（记录 [ZL:OPTIONAL_FAILED]，步骤照常推进）。
if ! has 3; then
  echo "[ZL:STEP:3]"
  pct 3 0
  ZL_CURRENT_STEP=3
  if zl_install 240 node nodejs npm; then
    # npm 自身重试 3 次；失败切 registry；仍失败不阻断容器初始化。
    NPM_REGISTRIES='${(listOf(m.npmRegistry) + m.npmRegistryAlt).joinToString(" ")}'
    npm config set fetch-retries 3 >/dev/null 2>&1
    npm config set fetch-retry-mintimeout 2000 >/dev/null 2>&1
    npm config set fetch-retry-maxtimeout 10000 >/dev/null 2>&1
    npm config set fetch-timeout 60000 >/dev/null 2>&1
    npm config set fund false >/dev/null 2>&1
    npm config set audit false >/dev/null 2>&1
    pct 3 100
    ok 3
    _ncycle=0
    while [ "@@_ncycle" -lt 2 ]; do
      _ncycle=@@(( _ncycle + 1 ))
      for reg in @@NPM_REGISTRIES; do
        npm config set registry "@@reg" >/dev/null 2>&1
        if timeout 300 npm install -g @opencode/cli > /tmp/zl-opencode-npm.log 2>&1; then
          _ncycle=9
          break
        fi
      done
      [ "@@_ncycle" -lt 9 ] && sleep 2
    done
  else
    bad 3 "node"
  fi
fi

if ! has 4; then
  echo "[ZL:STEP:4]"
  pct 4 0
  ZL_CURRENT_STEP=4
  if zl_install 300 go go; then pct 4 100; ok 4; else bad 4 "go"; fi
fi

if ! has 5; then
  echo "[ZL:STEP:5]"
  pct 5 0
  ZL_CURRENT_STEP=5
  if zl_install 300 rust rust cargo; then pct 5 100; ok 5; else bad 5 "rust"; fi
fi

rm -rf /var/cache/apk/* 2>/dev/null
echo "[ZL:FINISHED]"
    """
}
