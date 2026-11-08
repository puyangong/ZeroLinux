package com.zerolinux.app.opencode

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.opencode.mobile.OpenCodeMobile
import com.opencode.mobile.OpenCodeMobileConfig
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerConfig
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.core.AppPaths
import com.zerolinux.app.engine.ContainerShell
import com.zerolinux.app.service.OpenCodeServerService
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OpenCode 集成：
 * - 每个容器一个随机端口（避开 /proc/net/tcp* 里的监听端口）；
 * - 选中容器后在其 rootfs 内后台跑 `opencode serve`（密码来自容器配置）；
 * - 启动 AAR 客户端前，把该容器写入它的 localStorage 服务器列表并保存 token。
 */
object OpenCodeBridge {

    private const val TAG = "OpenCodeBridge"
    private const val DEFAULT_PASSWORD = "admin"
    private const val MIN_PORT = 20000
    private const val MAX_PORT = 60000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 最近一次安装/启动失败的关键行（只用于日志与提示，不参与重试判断）。 */
    @Volatile
    private var lastInstallError: String? = null

    fun serverId(uuid: String): String = "zl-$uuid"

    /**
     * 容器删除后同步移除 AAR 里的服务器条目、token 和对应 serve 进程。
     *
     * 该方法只允许在 `:ai` 进程调用（AiCommandService）：隐藏 WebView 必须
     * 和 AAR 的 localStorage 使用同一个 WebView 数据目录。删除流程不等待
     * WebView 初始化，写入完成后通过 [onDone] 通知调用方。
     */
    fun forgetServer(
        ctx: Context,
        uuid: String,
        portHint: Int = 0,
        onDone: () -> Unit = {}
    ) {
        val app = ctx.applicationContext
        val id = serverId(uuid)
        scope.launch {
            // 删除的正是最后一次进入 AI 的容器：清掉恢复记录，避免重启后打开已删服务器。
            if (AppStateStore.lastAiUuid(app) == uuid) AppStateStore.clearLastAiUuid(app)
            val cfg = runCatching { ContainerRepo(app).find(uuid) }.getOrNull()
            // 删除容器时配置可能已经被 ContainerOps.delete 删掉，调用方会把端口
            // 随命令一起带过来；没有端口说明从未启动过 AI，无需初始化隐藏 WebView。
            val port = if (portHint > 0) portHint else cfg?.opencodePort ?: 0
            if (port <= 0) {
                runCatching { OpenCodeMobile.secureDelete(app, id) }
                withContext(Dispatchers.Main) { onDone() }
                return@launch
            }
            serverJobs.remove(uuid)?.cancel()
            runCatching { killByPort(port) }
            runCatching { OpenCodeMobile.secureDelete(app, id) }
            withContext(Dispatchers.Main) {
                var web: WebView? = null
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish() {
                    if (!finished.compareAndSet(false, true)) return
                    runCatching { web?.destroy() }
                    onDone()
                }
                runCatching {
                    val view = WebView(app)
                    web = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            val js = """
                                (function(){
                                  var KEY='opencode-mobile-app';
                                  var raw=localStorage.getItem(KEY);
                                  if(!raw) return 'none';
                                  var root=JSON.parse(raw);
                                  var s=root.state||{};
                                  function norm(u){ return String(u||'').replace(/\/+$/,'').toLowerCase(); }
                                  var base='http://127.0.0.1:$port';
                                  // 同 baseUrl 的历史重复条目也一并清掉，避免容器已删还残留服务器。
                                  s.servers=(s.servers||[]).filter(function(x){
                                    if(x.id==='$id') return false;
                                    if($port>0 && norm(x.baseUrl)===norm(base)) return false;
                                    return true;
                                  });
                                  s.openedProjects=(s.openedProjects||[]).filter(function(p){return p.serverId!=='$id';});
                                  if(s.currentServerId==='$id'){
                                    s.currentServerId=(s.servers[0]&&s.servers[0].id)||null;
                                  }
                                  root.state=s; root.version=1;
                                  localStorage.setItem(KEY, JSON.stringify(root));
                                  return 'ok';
                                })()
                            """.trimIndent()
                            view.evaluateJavascript(js) {
                                finish()
                            }
                        }
                    }
                    view.loadDataWithBaseURL(
                        "https://app.local/",
                        "<html><body></body></html>",
                        "text/html",
                        "utf-8",
                        null
                    )
                }.onFailure {
                    Log.w(TAG, "forget server webview failed", it)
                    finish()
                }
            }
        }
    }

    fun passwordOf(cfg: ContainerConfig): String =
        cfg.opencodePassword.ifBlank { DEFAULT_PASSWORD }

    /** 本地 serve 是否真的在监听（AI 胶囊变绿的条件）。 */
    fun ping(port: Int): Boolean = AiStatus.ping(port)

    /** 顶栏 AI 入口：优先恢复上次离开 AI 时的页面与服务器。 */
    fun launchFromActive(activity: Activity, onDone: () -> Unit = {}) {
        val route = AppStateStore.lastAiRoute(activity)
        if (!route.isNullOrBlank()) {
            openSaved(activity, route, onDone)
        } else {
            launchFor(activity, null, onDone)
        }
    }

    /**
     * 打开指定容器的 AI 客户端；[uuid] 为空时用当前 active 容器。
     * 同时记录「最后一次进入 AI 的容器」，供重启与服务重建恢复。
     */
    fun launchFor(activity: Activity, uuid: String?, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val repo = ContainerRepo(activity)
                val cfg = (uuid?.let { repo.find(it) }) ?: repo.active()
                if (cfg == null) {
                    // 没有任何容器也允许进入 AI：AAR 会使用它自己保存的服务器列表
                    // （包括用户手动添加的远程服务器），容器只是本地 serve 的来源之一。
                    withContext(Dispatchers.Main) {
                        runCatching { OpenCodeMobile.launch(activity) }
                            .onFailure { Log.e(TAG, "launch OpenCode without container failed", it) }
                    }
                    return@launch
                }
                val ensured = ensurePort(repo, cfg)
                AppStateStore.setLastAiUuid(activity, ensured.uuid)
                OpenCodeServerService.start(activity, ensured.uuid)
                val already = isListening(ensured.opencodePort)
                if (!already) {
                    // 首次安装 opencode 可能几十秒：先给用户一个明确反馈，再等到端口
                    // 真正 LISTEN 后才打开客户端，避免 AAR 一进来就连不上。
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.ai_starting),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                ensureServer(activity.applicationContext, ensured, false)
                // ensureServer 可能耗时几十秒（首次安装 opencode）；期间容器
                // 可能已被删除。重新读一次最新配置，避免把已删除容器重新写回
                // AAR 的服务器列表，也避免用旧密码覆盖用户刚改的设置。
                val latest = repo.find(ensured.uuid)
                if (latest == null) {
                    withContext(Dispatchers.Main) {
                        runCatching { OpenCodeMobile.launch(activity) }
                            .onFailure { Log.e(TAG, "launch OpenCode after container deleted failed", it) }
                    }
                    return@launch
                }
                Log.i(
                    TAG,
                    "launch AI '${latest.label}' port=${latest.opencodePort} immediately (already=$already)"
                )
                // seedServer 的 WebView 是异步加载的；必须等它真正写入 localStorage
                // 并启动 AAR 页面后再回调 onDone，否则透明跳板会提前 finish。
                val launched = kotlinx.coroutines.CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    seedServer(activity, latest, makeCurrent = true) {
                        val password = passwordOf(latest)
                        val config = OpenCodeMobileConfig.builder()
                            .baseUrl("http://127.0.0.1:${latest.opencodePort}")
                            .username("opencode")
                            .token(password)
                            .directory("")
                            .watchEnabled(true)
                            .sound(true)
                            .notify(true)
                            .insecureTls(false)
                            .build()
                        runCatching { OpenCodeMobile.launch(activity, config, "/") }
                            .onFailure { Log.e(TAG, "launch OpenCode failed", it) }
                        launched.complete(Unit)
                    }
                    // 服务未就绪也允许进入 AI；连接状态由客户端自行重试。
                }
                withTimeoutOrNull(10_000L) { launched.await() }
            } finally {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    /**
     * 恢复上次 AI 页面：打开保存的 hash 路由，并按保存的服务器准备 serve。
     *
     * 不调用 seedServer，避免把用户手动添加的远程服务器覆盖成容器服务器。
     */
    fun openSaved(activity: Activity, route: String, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val repo = ContainerRepo(activity)
                val server = AppStateStore.lastAiServer(activity).orEmpty()
                val containerUuid = when {
                    server.startsWith("zl-") -> server.removePrefix("zl-")
                    server.isBlank() -> AppStateStore.lastAiUuid(activity)
                    else -> null // 手动添加的远程服务器：不需要本地 serve
                }
                if (containerUuid != null) {
                    repo.find(containerUuid)?.let { cfg ->
                        val ensured = ensurePort(repo, cfg)
                        OpenCodeServerService.start(activity, ensured.uuid)
                        ensureServer(activity.applicationContext, ensured, false)
                    }
                }
                withContext(Dispatchers.Main) {
                    runCatching { OpenCodeMobile.openPage(activity, route) }
                        .onFailure { Log.e(TAG, "restore OpenCode page failed", it) }
                }
            } finally {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    /**
     * 冷启动恢复 AI 页面：只拉起容器 serve，不重写 AAR 的 currentServerId。
     *
     * 用户可能在 AAR 里手动添加并选中了远程服务器；此时必须保留 AAR 自己
     * 持久化的服务器选择，否则会被容器服务器覆盖，回不到上次的连接状态。
     */
    fun openLast(activity: Activity, onDone: () -> Unit = {}) {
        val route = AppStateStore.lastAiRoute(activity)
        if (!route.isNullOrBlank()) {
            openSaved(activity, route, onDone)
            return
        }
        scope.launch {
            try {
                val repo = ContainerRepo(activity)
                val cfg = AppStateStore.lastAiUuid(activity)?.let { repo.find(it) }
                    ?: repo.active()
                if (cfg != null) {
                    val ensured = ensurePort(repo, cfg)
                    OpenCodeServerService.start(activity, ensured.uuid)
                    ensureServer(activity.applicationContext, ensured, false)
                }
                withContext(Dispatchers.Main) {
                    runCatching { OpenCodeMobile.launch(activity) }
                        .onFailure { Log.e(TAG, "restore OpenCode failed", it) }
                }
            } finally {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    /**
     * 仅由 `:ai` 的 OpenCodeServerService 调用（ACTION_START）：
     * 服务已经在前台运行，这里只负责分配端口、安装/启动 serve。
     * [restart] 为 true 时刷新 AAR 的 SecureStore/服务器列表（改密码场景）。
     */
    fun startForUuid(ctx: Context, uuid: String, restart: Boolean = false) {
        val app = ctx.applicationContext
        scope.launch {
            val repo = ContainerRepo(app)
            val latest = repo.find(uuid) ?: return@launch
            val ensured = ensurePort(repo, latest)
            ensureServer(app, ensured, restart)
            if (restart) {
                // ensureServer 可能刚跑完一次耗时安装；重新读取，容器已被删除
                // 或密码已再次修改时以最新配置为准，绝不把旧条目写回 AAR。
                val current = repo.find(uuid) ?: return@launch
                // 刚保存了新密码/端口：把 AAR 的 SecureStore 与服务器列表一起刷新，
                // 否则客户端仍拿旧 token 连接，表现就是「密码改了却一直连不上」。
                // 不把 currentServerId 切到这个容器：用户可能只是在改另一个容器。
                withContext(Dispatchers.Main) {
                    seedServer(app, current, makeCurrent = false) {}
                }
            }
        }
    }

    /**
     * 前台服务重建/开机时调用：恢复指定容器的 serve。
     * [uuid] 为空时优先最后一次进入 AI 的容器，其次当前 active 容器。
     */
    fun ensure(ctx: Context, uuid: String? = null) {
        scope.launch {
            val repo = ContainerRepo(ctx)
            val cfg = uuid?.let { repo.find(it) }
                ?: AppStateStore.lastAiUuid(ctx)?.let { repo.find(it) }
                ?: repo.active()
                ?: return@launch
            val ensured = ensurePort(repo, cfg)
            ensureServer(ctx.applicationContext, ensured, false)
        }
    }

    fun ensureActive(ctx: Context) = ensure(ctx, null)

    /**
     * 启动 AAR 后台 watcher：即使用户没有进入 AI、也不在终端，也能对
     * AAR 里选择的远程服务器播放完成提示音。
     *
     * `oc_watch` 配置由 AI 网页在选服务器/改设置时写入，包含 baseUrl 和 token，
     * 进程重启后仍在。`startWatch` 必须在可见上下文调用（Android 12+ 前台服务
     * 限制），因此由终端页 onResume 发命令给 `:ai` 的 AiCommandService 触发。
     */
    fun ensureBackgroundWatch(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            if (!OpenCodeMobile.isWatchEnabled(app)) {
                Log.i(TAG, "background watch disabled")
                return
            }
            OpenCodeMobile.startWatch(app)
            // AI 页面不可见时让 watcher 继续轮询；终端前台的提示音由 AiSound 外放。
            com.opencode.mobile.WatchService.setForeground(false)
            Log.i(TAG, "background watch started")
        }.onFailure { Log.w(TAG, "background watch start failed", it) }
    }

    /** 端口为 0 时分配一个；已分配则保留（同容器重启后不变）。 */
    private fun ensurePort(repo: ContainerRepo, cfg: ContainerConfig): ContainerConfig {
        if (cfg.opencodePort in MIN_PORT..MAX_PORT) return cfg
        val used = usedPorts()
        var picked = 0
        for (i in 0 until 200) {
            val candidate = Random.nextInt(MIN_PORT, MAX_PORT)
            if (candidate in used || !canBind(candidate)) continue
            picked = candidate
            break
        }
        if (picked == 0) picked = Random.nextInt(MIN_PORT, MAX_PORT)
        // 字段级更新：只写端口，避免用旧快照覆盖主进程刚保存的密码/设置。
        return runCatching {
            repo.update(cfg.uuid) { it.copy(opencodePort = picked) }
        }.getOrNull() ?: cfg.copy(opencodePort = picked)
    }

    private fun usedPorts(): Set<Int> {
        val ports = HashSet<Int>()
        for (name in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                File(name).readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    // sl local_address: 0100007F:1F90 -> 端口是最后一段十六进制
                    val local = parts.getOrNull(1) ?: return@forEach
                    val hex = local.substringAfterLast(':', "")
                    if (hex.length == 4) {
                        hex.toIntOrNull(16)?.let { ports += it }
                    }
                }
            }
        }
        return ports
    }

    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            true
        }
    }.getOrDefault(false)

    /** 真正连一下 127.0.0.1:port：TIME_WAIT 不会误判成服务还活着。 */
    private fun isListening(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            true
        }
    }.getOrDefault(false)

    private fun killByPort(port: Int) {
        val hexPort = "%04X".format(port)
        val inodes = HashSet<String>()
        for (name in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                File(name).readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 10) return@forEach
                    val local = parts[1]
                    if (!local.endsWith(":$hexPort", ignoreCase = true)) return@forEach
                    if (parts[3].uppercase() != "0A") return@forEach
                    parts[9].takeIf { it.isNotBlank() }?.let { inodes += it }
                }
            }
        }
        if (inodes.isEmpty()) return
        File("/proc").listFiles()?.forEach { procDir ->
            val pid = procDir.name.toIntOrNull() ?: return@forEach
            val fdDir = File(procDir, "fd")
            val fds = fdDir.list() ?: return@forEach
            for (fd in fds) {
                val link = runCatching {
                    java.nio.file.Files.readSymbolicLink(File(fdDir, fd).toPath()).toString()
                }.getOrNull() ?: continue
                val inode = link.removePrefix("socket:[").removeSuffix("]")
                if (link.startsWith("socket:[") && inode in inodes) {
                    Log.i(TAG, "kill stale opencode pid=$pid port=$port")
                    android.os.Process.killProcess(pid)
                    return@forEach
                }
            }
        }
    }

    /** 安装（若缺失）并启动该容器的 opencode serve；已在监听则跳过。 */
    private fun ensureServer(ctx: Context, cfg: ContainerConfig, restart: Boolean): Job? {
        val port = cfg.opencodePort
        if (port !in MIN_PORT..MAX_PORT) return null
        if (!restart) {
            // 已有协程在跑：把同一个 Job 交给调用方等待「端口真正 LISTEN」。
            serverJobs[cfg.uuid]?.takeIf { it.isActive }?.let { return it }
        }
        if (restart) {
            // 密码/端口变更：先取消旧的 serve 协程，再用独立的 proot 会话按端口杀掉遗留进程。
            serverJobs.remove(cfg.uuid)?.cancel()
            killByPort(port)
            runCatching {
                kotlinx.coroutines.runBlocking {
                    ContainerShell.exec(
                        ctx,
                        AppPaths.containerRootfs(ctx, cfg.uuid),
                        "pkill -9 -f 'opencode[ ]serve.*--port $port' 2>/dev/null || true",
                        timeoutMs = 15_000L
                    )
                }
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (isListening(port) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
        } else if (isListening(port)) {
            Log.i(TAG, "opencode already listening on $port (${cfg.label})")
            return null
        }
        val rootfs = AppPaths.containerRootfs(ctx, cfg.uuid)
        if (!rootfs.isDirectory) return null
        val password = passwordOf(cfg)
        val shellPassword = "'" + password.replace("'", "'\\''") + "'"
        val script = """
            export HOME=/root
            export PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin
            mkdir -p /opt/zerolinux
            NPM_LOG=/opt/zerolinux/opencode-npm.log
            : > "@@NPM_LOG"
            echo "ZLOC node=@@(command -v node || echo none) version=@@(node -v 2>/dev/null || echo none) npm=@@(command -v npm || echo none) npm_version=@@(npm -v 2>/dev/null || echo none) arch=@@(uname -m)"

            # node/npm 缺失时用系统包管理补一次。正常情况下 bootstrap 已从镜像
            # 装了官方 Node，这里只兜底 rootfs 不完整/老容器。
            if ! command -v npm >/dev/null 2>&1; then
              if command -v apt-get >/dev/null 2>&1; then
                echo "ZLOC install nodejs via apt"
                timeout 600 apt-get install -y --no-install-recommends nodejs npm >>"@@NPM_LOG" 2>&1
              elif command -v apk >/dev/null 2>&1; then
                echo "ZLOC install nodejs via apk"
                timeout 600 apk add --no-cache nodejs npm >>"@@NPM_LOG" 2>&1
              fi
            fi

            # 上次安装被超时/中断会在全局树里留下 .@opencode-XXXX staging 目录，
            # npm 下一次 rename 会直接 ENOTEMPTY（exit 217），于是永远装不上。
            # 只有「确实没装/版本不对」时才动全局树，避免每次重启都重下 100MB。
            OPENCODE_OK=0
            if command -v opencode >/dev/null 2>&1 && opencode --version 2>/dev/null | grep -q '2\.2\.5'; then
              OPENCODE_OK=1
              echo "ZLOC opencode already installed: @@(opencode --version 2>/dev/null | head -1)"
            fi

            if [ "@@OPENCODE_OK" -ne 1 ] && command -v npm >/dev/null 2>&1; then
              rm -rf /usr/local/lib/node_modules/@opencode 2>/dev/null
              rm -rf /usr/local/lib/node_modules/.@opencode* 2>/dev/null
              rm -rf /usr/lib/node_modules/@opencode 2>/dev/null
              rm -rf /usr/lib/node_modules/.@opencode* 2>/dev/null
              # 容器内独立 prefix：绕开 /usr/lib/node_modules 的全局 rename。
              rm -rf /opt/zerolinux/npm 2>/dev/null
              rm -rf /opt/zerolinux/ocbin 2>/dev/null
              echo "ZLOC npm install begin"
              timeout 900 npm install -g @opencode/cli@2.2.5 \
                --prefix /opt/zerolinux/npm --unsafe-perm --foreground-scripts \
                --no-audit --no-fund >>"@@NPM_LOG" 2>&1
              echo "ZLOC npm_exit=@@?"
              if [ -x /opt/zerolinux/npm/bin/opencode ]; then
                ln -sf /opt/zerolinux/npm/bin/opencode /usr/local/bin/opencode
              fi
            fi

            # 兜底：直接装平台二进制包。wrapper 的 postinstall 会再起一个 npm
            # 子进程去拉 100MB+ 的平台包，proot 下最容易卡在 rename；
            # 这里 --ignore-scripts 只取文件，再自己建 symlink。
            if ! command -v opencode >/dev/null 2>&1 || ! opencode --version >/dev/null 2>&1; then
              NARCH=@@(node -p 'process.arch' 2>/dev/null || echo arm64)
              MUSL=
              [ -f /etc/alpine-release ] && MUSL=-musl
              case "@@NARCH" in
                arm64) PKGS="@opencode/cli-linux-arm64@@MUSL" ;;
                x64) PKGS="@opencode/cli-linux-x64@@MUSL @opencode/cli-linux-x64-baseline@@MUSL" ;;
                arm) PKGS="@opencode/cli-linux-arm64@@MUSL" ;;
                *) PKGS="" ;;
              esac
              for PKG in @@PKGS; do
                echo "ZLOC fallback install @@PKG"
                rm -rf /opt/zerolinux/ocbin
                timeout 900 npm install -g "@@PKG@2.2.5" --prefix /opt/zerolinux/ocbin \
                  --ignore-scripts --no-audit --no-fund >>"@@NPM_LOG" 2>&1
                echo "ZLOC pkg_exit=@@? pkg=@@PKG"
                BIN=/opt/zerolinux/ocbin/lib/node_modules/@@PKG/bin/opencode
                [ -f "@@BIN" ] || BIN=/opt/zerolinux/ocbin/node_modules/@@PKG/bin/opencode
                if [ -f "@@BIN" ]; then
                  chmod 755 "@@BIN"
                  ln -sf "@@BIN" /usr/local/bin/opencode
                  opencode --version >/dev/null 2>&1 && break
                fi
              done
            fi

            echo "ZLOC after opencode=@@(command -v opencode || echo none) version=@@(opencode --version 2>/dev/null | head -1)"
            echo "ZLOC npm_log_tail_begin"
            tail -n 60 "@@NPM_LOG" 2>/dev/null
            echo "ZLOC npm_log_tail_end"

            if ! command -v opencode >/dev/null 2>&1; then
              echo "ZLOC ABORT opencode missing"
              exit 1
            fi

            PASSWORD=$shellPassword
            export OPENCODE_SERVER_PASSWORD="@@PASSWORD"
            echo "ZLOC starting serve on $port"
            # 前台运行：脚本保持存活，proot 不会回收 serve。
            opencode serve --hostname 0.0.0.0 --port $port
            code=@@?
            echo "ZLOC serve_exit=@@code"
            if [ "@@code" -ne 0 ]; then
              echo "ZLOC retry serve with --host"
              opencode serve --host 0.0.0.0 --port $port
              echo "ZLOC serve_retry_exit=@@?"
            fi
        """.trimIndent().replace("@@", "$")
        val job = scope.launch {
            runCatching {
                val result = ContainerShell.exec(
                    ctx,
                    rootfs,
                    script,
                    timeoutMs = 0L,
                    idleTimeoutMs = 0L,
                    onLine = { line ->
                        Log.i(TAG, "oc[${cfg.label}] $line")
                        if (line.contains("ZLOC npm_exit=") ||
                            line.contains("ZLOC pkg_exit=") ||
                            line.contains("ZLOC ABORT") ||
                            line.contains("ZLOC serve_exit=")
                        ) {
                            lastInstallError = line
                        }
                    }
                )
                Log.i(TAG, "opencode setup exit=${result.exitCode}")
            }.onFailure {
                // 容器重启/密码变更会主动 cancel 这个协程：这是正常路径，
                // 不要把它当成「启动失败」刷一条 warning。
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                Log.w(TAG, "start opencode failed (${cfg.label})", it)
            }
        }
        serverJobs[cfg.uuid] = job
        job.invokeOnCompletion { serverJobs.remove(cfg.uuid, job) }
        return job
    }

    /** 等端口真正 LISTEN；serve 协程提前结束（安装失败）时不再空等。 */
    private suspend fun waitListening(job: Job?, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isListening(port)) return true
            if (job != null && !job.isActive) return isListening(port)
            delay(500L)
        }
        return isListening(port)
    }

    /**
     * AAR 客户端把服务器列表存在 WebView localStorage 的 `opencode-mobile-app`。
     * 用同 origin 的隐藏 WebView 合并写入，token 走 SecureStore（key = serverId）。
     */
    private fun seedServer(
        ctx: Context,
        cfg: ContainerConfig,
        makeCurrent: Boolean,
        onDone: () -> Unit
    ) {
        val id = serverId(cfg.uuid)
        val port = cfg.opencodePort
        val name = cfg.label.replace("\\", "\\\\").replace("'", "\\'")
        val current = if (makeCurrent) "s.currentServerId=entry.id;" else ""
        // 现存容器的端口白名单：AI 打开时顺手清掉已删除容器的服务器条目，
        // 避免删除时那次隐藏 WebView 写入失败、或 AAR 又把旧状态写回来。
        val validPorts = runCatching {
            ContainerRepo(ctx).snapshot().items
                .mapNotNull { it.opencodePort.takeIf { p -> p > 0 } }
        }.getOrDefault(emptyList()).plus(port).distinct()
        val validJs = validPorts.joinToString(",")
        Handler(Looper.getMainLooper()).post {
            val web = WebView(ctx)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val js = """
                        (function(){
                          var KEY='opencode-mobile-app';
                          var raw=localStorage.getItem(KEY);
                          var root=raw?JSON.parse(raw):{state:{},version:1};
                          var s=root.state||{};
                          s.servers=s.servers||[];
                          function norm(u){ return String(u||'').replace(/\/+$/,'').toLowerCase(); }
                          var base='http://127.0.0.1:$port';
                          // 先清掉「容器已经删除但服务器条目还在」的残留：只处理 id 以 zl-
                          // 开头、且端口已不属于任何现存容器的条目；手动添加的远程服务器保留。
                          var validPorts=[$validJs];
                          var purged={};
                          s.servers=(s.servers||[]).filter(function(x){
                            if(String(x.id||'').indexOf('zl-')!==0) return true;
                            var m=/^https?:\/\/127\.0\.0\.1:(\d+)\/?$/.exec(String(x.baseUrl||''));
                            if(!m) return true;
                            if(validPorts.indexOf(Number(m[1]))>=0) return true;
                            purged[x.id]=true;
                            return false;
                          });
                          s.openedProjects=(s.openedProjects||[]).filter(function(p){
                            return !purged[p.serverId];
                          });
                          if(s.currentServerId && purged[s.currentServerId]) s.currentServerId=null;
                          var entry={
                            id:'$id', name:'$name',
                            baseUrl:base,
                            username:'opencode', token:'',
                            platform:'posix', lastUsed:Date.now()
                          };
                          // 已存在就更新，不重复新增。除了 id，还要按 baseUrl 判断：
                          // 旧版本的服务器条目可能用了别的 id，但指向同一个容器端口。
                          var i=-1;
                          for(var k=0;k<s.servers.length;k++){
                            if(s.servers[k].id===entry.id || norm(s.servers[k].baseUrl)===norm(base)){ i=k; break; }
                          }
                          if(i>=0){ s.servers[i]=Object.assign({}, s.servers[i], entry); }
                          else { s.servers.push(entry); }
                          // 同一容器只保留一条：清掉同 baseUrl 的其它重复项，并把
                          // 项目/当前服务器里指向旧条目的引用改到保留项上。
                          var removed={};
                          s.servers=s.servers.filter(function(x){
                            if(x.id===entry.id) return true;
                            if(norm(x.baseUrl)!==norm(base)) return true;
                            removed[x.id]=true;
                            return false;
                          });
                          s.openedProjects=(s.openedProjects||[]).map(function(p){
                            return removed[p.serverId]?Object.assign({}, p, {serverId:entry.id}):p;
                          });
                          if(removed[s.currentServerId]) s.currentServerId=entry.id;
                          $current
                          root.state=s; root.version=1;
                          localStorage.setItem(KEY, JSON.stringify(root));
                          return 'ok';
                        })()
                    """.trimIndent()
                    view.evaluateJavascript(js) {
                        runCatching {
                            OpenCodeMobile.secureSet(ctx, id, passwordOf(cfg))
                        }.onFailure { Log.w(TAG, "secureSet failed", it) }
                        runCatching { view.destroy() }
                        onDone()
                    }
                }
            }
            web.loadDataWithBaseURL(
                "https://app.local/",
                "<html><body></body></html>",
                "text/html",
                "utf-8",
                null
            )
        }
    }
}
