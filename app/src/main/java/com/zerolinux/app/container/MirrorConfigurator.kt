package com.zerolinux.app.container

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 容器内软件源规划。
 *
 * 现在 APK 内置了 Ubuntu / Debian / Kali / Alpine 官方 ca-certificates，
 * PRoot 启动时通过 -b 把证书目录挂到 /etc/ssl/certs，因此这里所有候选
 * 地址都只允许 HTTPS，不再降级到 HTTP。
 *
 * 选择逻辑在宿主机完成：对每个候选做一次 Range 请求测速，按响应时间
 * 排序，快的排前面；失败或超时的候选保留为后备。脚本里只写入选地址
 * 和后备顺序，不在容器里反复 wget 探测。
 */
object MirrorConfigurator {

    data class AptMirror(val base: String, val security: String)

    /** rustup 的 dist/update 镜像对，dist 决定组件下载地址。 */
    data class RustupMirror(val dist: String, val update: String)

    data class MirrorPlan(
        val distro: DistroId,
        val apt: List<AptMirror>,
        val alpine: List<String>,
        val node: List<String>,
        val go: List<String>,
        val goVersion: List<String>,
        val rustupInit: List<String>,
        val rustup: List<RustupMirror>,
        val npmRegistry: String,
        val npmRegistryAlt: List<String>,
        val pipIndex: String,
        val goProxy: String,
        val alpineRel: String
    ) {
        val aptBase: String get() = apt.firstOrNull()?.base.orEmpty()
        val securityBase: String get() = apt.firstOrNull()?.security.orEmpty()
        val alpineBase: String get() = alpine.firstOrNull().orEmpty()
        val nodeBase: String get() = node.firstOrNull().orEmpty()
        val goBase: String get() = go.firstOrNull().orEmpty()
    }

    // ---- 候选表：全部 HTTPS ----

    private val UBUNTU_APT = listOf(
        AptMirror("https://mirrors.aliyun.com/ubuntu", "https://mirrors.aliyun.com/ubuntu"),
        AptMirror("https://mirrors.tuna.tsinghua.edu.cn/ubuntu", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu"),
        AptMirror("https://mirrors.ustc.edu.cn/ubuntu", "https://mirrors.ustc.edu.cn/ubuntu"),
        AptMirror("https://archive.ubuntu.com/ubuntu", "https://security.ubuntu.com/ubuntu")
    )

    private val DEBIAN_APT = listOf(
        AptMirror("https://mirrors.aliyun.com/debian", "https://mirrors.aliyun.com/debian-security"),
        AptMirror("https://mirrors.tuna.tsinghua.edu.cn/debian", "https://mirrors.tuna.tsinghua.edu.cn/debian-security"),
        AptMirror("https://mirrors.ustc.edu.cn/debian", "https://mirrors.ustc.edu.cn/debian-security"),
        AptMirror("https://deb.debian.org/debian", "https://security.debian.org/debian-security")
    )

    private val KALI_APT = listOf(
        AptMirror("https://mirrors.aliyun.com/kali", "https://mirrors.aliyun.com/kali"),
        AptMirror("https://mirrors.tuna.tsinghua.edu.cn/kali", "https://mirrors.tuna.tsinghua.edu.cn/kali"),
        AptMirror("https://mirrors.ustc.edu.cn/kali", "https://mirrors.ustc.edu.cn/kali"),
        AptMirror("https://http.kali.org/kali", "https://http.kali.org/kali")
    )

    private val ALPINE = listOf(
        "https://mirrors.aliyun.com/alpine",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "https://mirrors.ustc.edu.cn/alpine",
        "https://dl-cdn.alpinelinux.org/alpine"
    )

    private val NODE = listOf(
        "https://npmmirror.com/mirrors/node",
        "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release",
        "https://nodejs.org/dist"
    )

    private val GO = listOf(
        "https://mirrors.aliyun.com/golang",
        "https://mirrors.ustc.edu.cn/golang",
        "https://go.dev/dl"
    )

    private val GO_VERSION = listOf(
        "https://golang.google.cn/VERSION?m=text",
        "https://go.dev/VERSION?m=text"
    )

    /**
     * rustup dist 镜像。中国大陆优先：rsproxy 支持 dist + rustup 自更新；
     * USTC / NJU 只镜像 dist，rustup-init 仍从官方脚本或 rsproxy 拉取。
     */
    private val RUSTUP = listOf(
        RustupMirror("https://rsproxy.cn", "https://rsproxy.cn/rustup"),
        RustupMirror("https://mirrors.ustc.edu.cn/rust-static", "https://mirrors.ustc.edu.cn/rust-static/rustup"),
        RustupMirror("https://mirror.nju.edu.cn/rustup", "https://mirror.nju.edu.cn/rustup/rustup"),
        RustupMirror("https://static.rust-lang.org", "https://static.rust-lang.org/rustup")
    )

    private val RUSTUP_INIT = listOf(
        "https://rsproxy.cn/rustup-init.sh",
        "https://sh.rustup.rs"
    )

    private val NPM = listOf(
        "https://registry.npmmirror.com",
        "https://registry.npmjs.org"
    )

    private val PIP = listOf(
        "https://mirrors.aliyun.com/pypi/simple",
        "https://pypi.org/simple"
    )

    /**
     * 生成一次安装使用的镜像计划。所有网络探测都发生在宿主机，
     * 每个候选只读 64 KiB，失败不会阻塞安装，只是排到后备位置。
     */
    suspend fun plan(@Suppress("UNUSED_PARAMETER") ctx: Context, distro: DistroId): MirrorPlan {
        val aptCandidates = when (distro) {
            DistroId.UBUNTU -> UBUNTU_APT
            DistroId.DEBIAN -> DEBIAN_APT
            DistroId.KALI -> KALI_APT
            DistroId.ALPINE -> emptyList()
        }
        val aptSorted = rank(aptCandidates) { mirror ->
            val suite = when (distro) {
                DistroId.UBUNTU -> "resolute"
                DistroId.DEBIAN -> "bookworm"
                DistroId.KALI -> "kali-rolling"
                DistroId.ALPINE -> "bookworm"
            }
            "${mirror.base}/dists/$suite/Release"
        }
        val alpineSorted = rank(ALPINE) { base -> "$base/v3.21/main/APKINDEX.tar.gz" }
        val nodeSorted = rank(NODE) { base -> "$base/latest/SHASUMS256.txt" }
        val goSorted = rank(GO) { base -> "$base/go1.23.0.linux-amd64.tar.gz" }
        val rustupSorted = rankBySpeed(RUSTUP, sampleBytes = 512 * 1024) { "${it.dist}/dist/channel-rust-stable.toml" }
        val rustupInitSorted = rank(RUSTUP_INIT) { it }
        val npmSorted = rank(NPM) { "$it/-/ping" }
        val pipSorted = rank(PIP) { "$it/pip/" }

        return MirrorPlan(
            distro = distro,
            apt = aptSorted,
            alpine = alpineSorted,
            node = nodeSorted,
            go = goSorted,
            goVersion = if (goSorted.firstOrNull()?.contains("aliyun") == true ||
                goSorted.firstOrNull()?.contains("ustc") == true
            ) GO_VERSION else GO_VERSION.reversed(),
            rustupInit = rustupInitSorted,
            rustup = rustupSorted,
            npmRegistry = npmSorted.firstOrNull() ?: "https://registry.npmmirror.com",
            npmRegistryAlt = npmSorted.drop(1),
            pipIndex = pipSorted.firstOrNull() ?: "https://mirrors.aliyun.com/pypi/simple",
            goProxy = "https://goproxy.cn,direct",
            alpineRel = "3.21"
        )
    }

    /** 并行测速，成功的按耗时排序，失败的保持原顺序放在后面。 */
    private suspend fun <T> rank(items: List<T>, probeUrl: (T) -> String): List<T> {
        if (items.isEmpty()) return items
        val results = coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    val url = probeUrl(item)
                    val bench = runCatching { Http.benchmarkHttps(url, timeoutMs = 3_000) }.getOrNull()
                    item to bench
                }
            }.awaitAll()
        }
        return results
            .sortedWith(compareBy({ it.second == null }, { it.second?.ms ?: Long.MAX_VALUE }))
            .map { it.first }
    }

    /**
     * 按真实吞吐排序：读一段较大的样本，算 bytes/ms，快的排前面。
     * rustup 要下 ~100 MB 的 3 个组件，只看首字节延迟会选到“延迟低但限速”的源。
     */
    private suspend fun <T> rankBySpeed(
        items: List<T>,
        sampleBytes: Int,
        probeUrl: (T) -> String
    ): List<T> {
        if (items.isEmpty()) return items
        val results = coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    val url = probeUrl(item)
                    val bench = runCatching {
                        Http.benchmarkHttps(url, maxBytes = sampleBytes, timeoutMs = 6_000)
                    }.getOrNull()
                    item to bench
                }
            }.awaitAll()
        }
        return results
            .sortedWith(
                compareByDescending {
                    it.second?.let { b -> b.bytes.toDouble() / b.ms.coerceAtLeast(1) } ?: -1.0
                }
            )
            .map { it.first }
    }

    /**
     * 生成容器内换源脚本。apt 系写入 sources.list，Alpine 写入
     * repositories + 后备源切换函数。所有地址都是 HTTPS。
     */
    fun script(distro: DistroId, plan: MirrorPlan): String {
        val repoScript = if (distro == DistroId.ALPINE) alpineScript(plan) else aptScript(distro, plan)
        return repoScript + "\n" + ecosystemScript(plan)
    }

    private fun aptScript(distro: DistroId, plan: MirrorPlan): String {
        val mirrors = plan.apt.joinToString(" ") { "${it.base}|${it.security}" }
        val first = plan.apt.firstOrNull()?.let { "${it.base}|${it.security}" }.orEmpty()
        val lines = when (distro) {
            DistroId.UBUNTU -> """
                echo "deb @@_base @@ZL_APT_SUITE main restricted universe multiverse"
                echo "deb @@_base @@ZL_APT_SUITE-updates main restricted universe multiverse"
                echo "deb @@_sec @@ZL_APT_SUITE-security main restricted universe multiverse"
            """.trimIndent()
            DistroId.KALI -> """
                echo "deb @@_base kali-rolling main contrib non-free non-free-firmware"
            """.trimIndent()
            else -> """
                echo "deb @@_base @@ZL_APT_SUITE main contrib non-free non-free-firmware"
                echo "deb @@_base @@ZL_APT_SUITE-updates main contrib non-free non-free-firmware"
                echo "deb @@_sec @@ZL_APT_SUITE-security main contrib non-free non-free-firmware"
            """.trimIndent()
        }.prependIndent("            ")
        return """
            #!/bin/sh
            set +e
            export DEBIAN_FRONTEND=noninteractive
            mkdir -p /opt/zerolinux

            # 备份并清空旧源，避免 deb822 与 list 同时生效。
            for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
              [ -f "@@f" ] || continue
              case "@@f" in *.zl.bak) continue ;; esac
              cp "@@f" "@@f.zl.bak" 2>/dev/null || true
              : > "@@f" 2>/dev/null || true
            done
            rm -f /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list.d/ubuntu.sources

            cat > /opt/zerolinux/apt-mirror-lib.sh <<'ZL_APT_EOF'
            if [ -f /etc/os-release ]; then . /etc/os-release; fi
            ZL_APT_SUITE="@@{VERSION_CODENAME:-@@{VERSION_ID:-stable}}"
            [ -z "@@ZL_APT_SUITE" ] && ZL_APT_SUITE=stable
            ZL_APT_MIRRORS='$mirrors'
            ZL_APT_CURRENT='$first'

            # Android 上 IPv6 经常不可达；apt 默认会先试 IPv6 导致大包下载超时。
            mkdir -p /etc/apt/apt.conf.d
            cat > /etc/apt/apt.conf.d/99zerolinux <<'ZL_APT_CONF'
            Acquire::ForceIPv4 "true";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            Acquire::Retries "3";
            # 容器里没有配置文件冲突需要人工确认，统一保留旧文件。
            Dpkg::Options {
              "--force-confdef";
              "--force-confold";
            };
            ZL_APT_CONF

            zl_apt_write() {
              _pair="@@1"
              [ -z "@@_pair" ] && _pair="@@ZL_APT_CURRENT"
              _base="@@{_pair%%|*}"
              _sec="@@{_pair#*|}"
              {
            $lines
              } > /etc/apt/sources.list.zl.new
              mv /etc/apt/sources.list.zl.new /etc/apt/sources.list
            }

            zl_apt_next() {
              _first=
              _next=
              _seen=0
              for _pair in @@ZL_APT_MIRRORS; do
                [ -z "@@_first" ] && _first="@@_pair"
                if [ "@@_seen" = 1 ] && [ -z "@@_next" ]; then _next="@@_pair"; fi
                [ "@@_pair" = "@@ZL_APT_CURRENT" ] && _seen=1
              done
              [ -z "@@_next" ] && _next="@@_first"
              [ "@@_next" = "@@ZL_APT_CURRENT" ] && return 1
              ZL_APT_CURRENT="@@_next"
              zl_apt_write "@@ZL_APT_CURRENT"
              return 0
            }

            # apt 自身每源重试 3 次；失败后由调用方 zl_apt_next 切换镜像。
            zl_apt_update() {
              rm -rf /var/lib/apt/lists/* 2>/dev/null
              timeout 300 apt-get -o Acquire::Retries=3 update -y > /tmp/zl-apt-update.log 2>&1
              [ -n "@@(ls /var/lib/apt/lists/*Packages* 2>/dev/null)" ]
            }

            # 从 apt / dpkg 日志里提取配置失败的包名（去重）。
            zl_dpkg_failed_pkgs() {
              {
                sed -n 's/^dpkg: error processing package \([^ ]*\) (--configure).*/\1/p' /tmp/zl-apt-install.log 2>/dev/null
                sed -n 's/^dpkg: error processing package \([^ ]*\) (--configure).*/\1/p' /tmp/zl-dpkg-fix.log 2>/dev/null
              } | sort -u
            }

            # 只把真正维护者脚本失败的包当作可修复目标；
            # 单纯 "dependency problems" 的包等依赖装好会自己变好。
            # 失败文案在 Debian / Kali 上有多种写法，这里全部覆盖。
            zl_pkg_script_failed() {
              for _lf in /tmp/zl-apt-install.log /tmp/zl-dpkg-fix.log; do
                [ -f "@@_lf" ] || continue
                grep -A3 -F "error processing package @@1 " "@@_lf" 2>/dev/null \
                    | grep -Eq 'post-installation script|postinst|maintainer script|pre-installation script|post-removal script' \
                    && return 0
              done
              return 1
            }

            # ca-certificates-java 的 postinst / trigger 会启动 JRE 重建
            # /etc/ssl/certs/java/cacerts。PRoot 容器里没有可用 JRE（或 JRE 本身在
            # PRoot 下跑不起来），于是 postinst 必然 exit 1，并连带卡住
            # ca-certificates 的配置。容器只需要系统 CA，直接关闭 Java 证书库更新。
            zl_disable_java_cacerts() {
              mkdir -p /etc/default /etc/ssl/certs/java /etc/ca-certificates/update.d 2>/dev/null || true
              if [ -f /etc/default/cacerts ]; then
                sed -i 's/^cacerts_updates=.*/cacerts_updates=no/' /etc/default/cacerts 2>/dev/null || true
                grep -q '^cacerts_updates=' /etc/default/cacerts 2>/dev/null \
                    || echo 'cacerts_updates=no' >> /etc/default/cacerts 2>/dev/null || true
              else
                printf 'cacerts_updates=no\n' > /etc/default/cacerts 2>/dev/null || true
              fi
              if [ -e /etc/ca-certificates/update.d/jks-keystore ] \
                  && [ ! -e /etc/ca-certificates/update.d/jks-keystore.zl-disabled ]; then
                mv -f /etc/ca-certificates/update.d/jks-keystore \
                      /etc/ca-certificates/update.d/jks-keystore.zl-disabled 2>/dev/null || true
              fi
              [ -e /etc/ssl/certs/java/cacerts ] || : > /etc/ssl/certs/java/cacerts 2>/dev/null || true
            }

            # PRoot 里 systemd 不是 PID 1，machine-id / udev 相关调用会直接报错。
            # 保留原二进制为 *.zl-real，装包期间换成 no-op。
            zl_proot_shim() {
              for _b in systemd-machine-id-setup udevadm; do
                for _d in /usr/bin /bin /usr/sbin /sbin; do
                  [ -f "@@_d/@@_b" ] || continue
                  [ -f "@@_d/@@_b.zl-real" ] || cp -a "@@_d/@@_b" "@@_d/@@_b.zl-real" 2>/dev/null || continue
                  printf '#!/bin/sh\nexit 0\n' > "@@_d/@@_b" 2>/dev/null || true
                  chmod 755 "@@_d/@@_b" 2>/dev/null || true
                done
              done
            }

            # 兜底：把 postinst 挪走，让 dpkg 能标记为已配置。原文件保留为 .zl-disabled。
            zl_neuter_postinst() {
              for _f in /var/lib/dpkg/info/@@1.postinst /var/lib/dpkg/info/@@1:*.postinst; do
                [ -f "@@_f" ] || continue
                mv -f "@@_f" "@@_f.zl-disabled" 2>/dev/null || true
                echo "[ZL:DPKG:postinst-disabled] pkg=@@1"
              done
            }

            # dpkg 错误不能靠换源解决，这里做针对性修复。
            zl_dpkg_repair() {
              export DEBIAN_FRONTEND=noninteractive
              export SYSTEMD_OFFLINE=1
              mkdir -p /run/sshd /etc/ssl/certs /var/lib/systemd /var/log 2>/dev/null || true
              : > /tmp/zl-dpkg-fix.log
              timeout 120 dpkg --configure -a >>/tmp/zl-dpkg-fix.log 2>&1 || true
              _pkgs="@@(zl_dpkg_failed_pkgs)"
              [ -n "@@_pkgs" ] || return 1
              for _p in @@_pkgs; do
                echo "[ZL:DPKG:repair] pkg=@@_p"
                case "@@_p" in
                  openssh-server*)
                    mkdir -p /run/sshd
                    ssh-keygen -A >/dev/null 2>&1 || true
                    ;;
                  ca-certificates-java*)
                    # 没有可用 JRE 时，jks-keystore 钩子必然失败；先关掉再配置。
                    zl_disable_java_cacerts
                    ;;
                  ca-certificates*)
                    # ProotLauncher 已经把 Android 系统 CA 写进 ca-certificates.crt；
                    # update-ca-certificates 失败也不影响 HTTPS，只要文件在。
                    mkdir -p /etc/ssl/certs
                    [ -s /etc/ssl/certs/ca-certificates.crt ] || : > /etc/ssl/certs/ca-certificates.crt
                    zl_disable_java_cacerts
                    timeout 180 update-ca-certificates --fresh >>/tmp/zl-dpkg-fix.log 2>&1 || true
                    ;;
                  libc-bin*)
                    ldconfig >/dev/null 2>&1 || true
                    ;;
                  systemd*|udev*|dbus*|polkitd*|udisks2*|libpam-systemd*)
                    zl_proot_shim
                    ;;
                  initramfs-tools*|linux-image-*|linux-headers-*|grub-*)
                    # 容器不启动内核，这些 postinst 必然失败。
                    zl_neuter_postinst "@@_p"
                    ;;
                esac
                timeout 120 dpkg --configure "@@_p" >>/tmp/zl-dpkg-fix.log 2>&1 || true
              done
              # 仍失败的 postinst：先重新 configure 确认真的还失败，再兜底禁用。
              # 日志是追加写的，前面轮次的旧报错会让包名一直留在失败列表里。
              for _p in @@(zl_dpkg_failed_pkgs); do
                timeout 120 dpkg --configure "@@_p" >>/tmp/zl-dpkg-fix.log 2>&1 && continue
                if zl_pkg_script_failed "@@_p"; then
                  zl_neuter_postinst "@@_p"
                  timeout 120 dpkg --configure --force-all "@@_p" >>/tmp/zl-dpkg-fix.log 2>&1 || true
                fi
              done
              timeout 120 dpkg --configure -a >>/tmp/zl-dpkg-fix.log 2>&1 || true
              if [ -z "@@(dpkg --audit 2>/dev/null)" ]; then
                echo "[ZL:DPKG:repair-ok]"
                return 0
              fi
              echo "[ZL:DPKG:repair-failed]"
              dpkg --audit 2>/dev/null | head -8
              tail -12 /tmp/zl-dpkg-fix.log 2>/dev/null
              return 1
            }

            zl_apt_install() {
              _cycle=0
              [ -n "@@(ls /var/lib/apt/lists/*Packages* 2>/dev/null)" ] || zl_apt_update || true
              # 容器里禁止包管理器尝试启动 systemd 服务；否则 openssh-server
              # 等 postinst 可能一直卡住，表现为 step 2 停在 30% 不动。
              mkdir -p /usr/sbin /run
              printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d 2>/dev/null || true
              chmod +x /usr/sbin/policy-rc.d 2>/dev/null || true
              # systemctl 在 PRoot 里连不上 bus；离线模式让 postinst 里的调用变 no-op。
              export SYSTEMD_OFFLINE=1
              # Kali / Debian 的 ca-certificates-java 会在 ca-certificates 升级时
              # 触发 JRE 重建证书库；容器里没有 JRE，必然失败并卡住整个 dpkg。
              # 装包前就关掉，避免第一步就进入半配置状态。
              zl_disable_java_cacerts
              # 先修上次中断留下的半配置状态，避免带着坏状态开始装。
              timeout 180 dpkg --configure -a >/tmp/zl-dpkg-fix.log 2>&1 || true
              while [ "@@_cycle" -lt 8 ]; do
                _cycle=@@(( _cycle + 1 ))
                _attempt=0
                _repair_tries=0
                while [ "@@_attempt" -lt 3 ]; do
                  _attempt=@@(( _attempt + 1 ))
                  if [ -n "@@ZL_CURRENT_STEP" ]; then
                    _p=@@(( 20 + _cycle * 8 ))
                    [ "@@_p" -gt 90 ] && _p=90
                    pct "@@ZL_CURRENT_STEP" "@@_p"
                  fi
                  export DEBIAN_FRONTEND=noninteractive
                  timeout 120 dpkg --configure -a >/tmp/zl-dpkg-fix.log 2>&1 || true
                  rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock
                  # 断线重连从 0 重下：只清掉未完成的 deb 分片，不动已下载/已安装的包。
                  rm -rf /var/cache/apt/archives/partial/* 2>/dev/null
                  # 先模拟一次拿到本次要装的包总数，用于真实百分比。
                  apt-get -s install -y --no-install-recommends "@@@" \
                      > /tmp/zl-apt-plan.log 2>/dev/null || true
                  _total=@@(grep -c '^Inst ' /tmp/zl-apt-plan.log 2>/dev/null || true)
                  [ -n "@@_total" ] || _total=0
                  _t0=@@(date +%s)
                  _last_size=0
                  _last_change="@@_t0"
                  _switched=0
                  timeout 1200 apt-get -o Acquire::Retries=3 install -y --no-install-recommends "@@@" \
                      > /tmp/zl-apt-install.log 2>&1 &
                  _pid=@@!
                  while kill -0 "@@_pid" 2>/dev/null; do
                    sleep 3
                    _now=@@(date +%s)
                    _el=@@(( _now - _t0 ))
                    [ "@@_el" -gt 0 ] || _el=1
                    _size=@@(wc -c < /tmp/zl-apt-install.log 2>/dev/null || echo 0)
                    if [ "@@_size" -gt "@@_last_size" ]; then
                      _last_size="@@_size"
                      _last_change="@@_now"
                    fi
                    _done=@@(grep -c '^Setting up ' /tmp/zl-apt-install.log 2>/dev/null || true)
                    [ -n "@@_done" ] || _done=0
                    if [ "@@_total" -gt 0 ]; then
                      _p=@@(( 20 + _done * 70 / _total ))
                    else
                      _p=@@(( 20 + _el * 70 / 1200 ))
                    fi
                    [ "@@_p" -gt 90 ] && _p=90
                    [ -n "@@ZL_CURRENT_STEP" ] && pct "@@ZL_CURRENT_STEP" "@@_p"
                    echo "[ZL:APT:progress] elapsed=@@{_el}s done=@@{_done}/@@{_total} size=@@{_size}"
                    _idle=@@(( _now - _last_change ))
                    if [ "@@_idle" -ge 120 ]; then
                      echo "[ZL:MIRROR:apt-stalled] idle=@@{_idle}s"
                      kill "@@_pid" 2>/dev/null
                      _switched=1
                      break
                    fi
                    if [ "@@_el" -ge 1200 ]; then
                      echo "[ZL:APT:timeout] elapsed=@@{_el}s"
                      kill "@@_pid" 2>/dev/null
                      break
                    fi
                  done
                  wait "@@_pid" 2>/dev/null
                  _rc=@@?
                  _audit="@@(dpkg --audit 2>/dev/null)"
                  if [ "@@_rc" -eq 0 ] && [ -z "@@_audit" ]; then
                    return 0
                  fi
                  tail -3 /tmp/zl-apt-install.log 2>/dev/null
                  _conf_err=@@(grep -c 'dpkg: error processing package .* (--configure)' /tmp/zl-apt-install.log 2>/dev/null || true)
                  [ -n "@@_conf_err" ] || _conf_err=0
                  _arch_err=@@(grep -c 'dpkg: error processing archive' /tmp/zl-apt-install.log 2>/dev/null || true)
                  [ -n "@@_arch_err" ] || _arch_err=0
                  # apt 遇到半配置状态会直接拒绝运行，这种也必须先修 dpkg。
                  _interrupted=@@(grep -c 'dpkg was interrupted' /tmp/zl-apt-install.log 2>/dev/null || true)
                  [ -n "@@_interrupted" ] || _interrupted=0
                  _need_repair=0
                  [ "@@_conf_err" -gt 0 ] && _need_repair=1
                  [ "@@_interrupted" -gt 0 ] && _need_repair=1
                  [ -n "@@_audit" ] && _need_repair=1
                  # dpkg/postinst 错误换源没用：先就地修复，修好继续用同一个源。
                  if [ "@@_need_repair" -eq 1 ] && [ "@@_repair_tries" -lt 2 ]; then
                    _repair_tries=@@(( _repair_tries + 1 ))
                    if zl_dpkg_repair; then
                      echo "[ZL:DPKG:repaired] retry same mirror"
                      _attempt=@@(( _attempt - 1 ))
                      continue
                    fi
                  fi
                  if [ "@@_conf_err" -gt 0 ]; then
                    echo "[ZL:DPKG:unrepaired] packages=@@(zl_dpkg_failed_pkgs | tr '\n' ' ')"
                    return 1
                  fi
                  timeout 180 apt-get -f install -y >/tmp/zl-apt-fix.log 2>&1 || true
                  # 下载包损坏：清掉缓存 deb，换源重下。
                  if [ "@@_arch_err" -gt 0 ]; then
                    rm -rf /var/cache/apt/archives/*.deb 2>/dev/null
                  fi
                  [ "@@_switched" -eq 1 ] && break
                  sleep 2
                done
                echo "[ZL:MIRROR:apt-switch] retry after install failure"
                zl_apt_next || return 1
                zl_apt_update || true
              done
              return 1
            }
            ZL_APT_EOF

            . /opt/zerolinux/apt-mirror-lib.sh
            zl_apt_write "@@ZL_APT_CURRENT"
            echo "[ZL:MIRROR:apt] @@ZL_APT_CURRENT suite=@@ZL_APT_SUITE"
        """.trimIndent().replace("@@", "$")
    }

    private fun alpineScript(plan: MirrorPlan): String = """
        #!/bin/sh
        set +e
        REL=@@(cut -d. -f1,2 /etc/alpine-release 2>/dev/null)
        [ -z "@@REL" ] && REL=${plan.alpineRel}
        ZL_ALPINE_REL="@@REL"
        ZL_ALPINE_MIRRORS='${plan.alpine.joinToString(" ")}'
        ZL_ALPINE_CURRENT='${plan.alpineBase}'
        zl_write_repos() {
          _m="@@1"
          [ -z "@@_m" ] && _m="@@ZL_ALPINE_CURRENT"
          printf '%s\n' "@@_m/v@@ZL_ALPINE_REL/main" "@@_m/v@@ZL_ALPINE_REL/community" > /etc/apk/repositories
        }
        zl_next_mirror() {
          _first=
          _next=
          _seen=0
          for _m in @@ZL_ALPINE_MIRRORS; do
            [ -z "@@_first" ] && _first="@@_m"
            if [ "@@_seen" = 1 ] && [ -z "@@_next" ]; then _next="@@_m"; fi
            [ "@@_m" = "@@ZL_ALPINE_CURRENT" ] && _seen=1
          done
          [ -z "@@_next" ] && _next="@@_first"
          [ "@@_next" = "@@ZL_ALPINE_CURRENT" ] && return 1
          ZL_ALPINE_CURRENT="@@_next"
          zl_write_repos "@@ZL_ALPINE_CURRENT"
          return 0
        }
        zl_write_repos "@@ZL_ALPINE_CURRENT"
        echo "[ZL:MIRROR:apk] @@ZL_ALPINE_CURRENT rel=@@REL"
    """.trimIndent().replace("@@", "$")

    fun ecosystemScript(plan: MirrorPlan): String = """
        #!/bin/sh
        mkdir -p /etc/profile.d /root
        cat > /etc/profile.d/zerolinux-mirror.sh <<'EOF'
        export npm_config_registry='${plan.npmRegistry}'
        export NPM_CONFIG_REGISTRY='${plan.npmRegistry}'
        export PIP_INDEX_URL='${plan.pipIndex}'
        export PIP_TRUSTED_HOST='mirrors.aliyun.com'
        export RUSTUP_DIST_SERVER='${plan.rustup.firstOrNull()?.dist ?: "https://rsproxy.cn"}'
        export RUSTUP_UPDATE_ROOT='${plan.rustup.firstOrNull()?.update ?: "https://rsproxy.cn/rustup"}'
        export GOPROXY='${plan.goProxy}'
        export GOFLAGS='-mod=mod'
        EOF
        cat > /root/.npmrc <<'EOF'
        registry=${plan.npmRegistry}
        fund=false
        audit=false
        EOF
        cat > /etc/pip.conf <<'EOF'
        [global]
        index-url=${plan.pipIndex}
        trusted-host=mirrors.aliyun.com
        disable-pip-version-check=true
        EOF
        echo "[ZL:MIRROR:eco] npm=${plan.npmRegistry} pip=${plan.pipIndex}"
    """.trimIndent()
}
