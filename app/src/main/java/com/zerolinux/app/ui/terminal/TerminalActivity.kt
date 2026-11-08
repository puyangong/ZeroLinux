@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.zerolinux.app.ui.terminal

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerConfig
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.ui.common.ZlObserveResume
import com.zerolinux.app.engine.ProotLauncher
import com.zerolinux.app.engine.SessionRegistry
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.service.TerminalHostService
import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.engine.SessionSignals
import com.zerolinux.app.opencode.AiStatus
import com.zerolinux.app.opencode.AppStateStore
import com.zerolinux.app.ui.common.ZlTerminalTopBar
import com.zerolinux.app.ui.container.ContainerListActivity
import com.zerolinux.app.ui.container.ContainerSettingsActivity
import kotlin.math.roundToInt
import com.zerolinux.app.ui.container.distroName
import com.zerolinux.app.ui.files.FileDrawer
import com.zerolinux.app.ui.theme.ZeroLinuxTheme
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMonoBody
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZlRunning
import com.zerolinux.app.ui.theme.ZlSurface
import com.zerolinux.app.ui.theme.ZlUnread
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.container.TaskStateStore
import com.zerolinux.app.ui.container.TaskBanners
import com.zerolinux.app.web.PtyWebBridge
import com.zerolinux.app.web.PtyWebHost
import kotlinx.coroutines.launch

/**
 * 终端页。xterm.js 跑在一个 WebView 里，顶栏与会话抽屉是 Compose。
 *
 * 会话本身（proot 子进程）归 SessionRegistry 管，
 * WebView 只是显示层；WebView 重建时按 sid 重新 create 即可。
 */
class TerminalActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TerminalHostService.start(this)
        // 左右边缘要用来开抽屉，必须向系统申请手势排除，否则边缘那 24dp
        // 永远归系统「返回」手势，抽屉根本滑不动。Compose 的
        // Modifier.systemGestureExclusion 只作用于自身节点，这里直接在
        // decorView 上设整条边的矩形更可靠。
        applyEdgeGestureExclusion()

        setContent {
            ZeroLinuxTheme {
                Screen()
            }
        }

        // 冷启动恢复：上次停留在 AI 页时，回到 AAR 自己保存的最后一个服务器
        // （可能是手动添加的远程服务器，不能强制切回容器服务器）。
        if (intent?.getBooleanExtra("open_ai", false) == true) {
            window.decorView.postDelayed({
                AiIntents.openLast(this)
            }, 800L)
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到终端：恢复向后端 WebView 推帧，并把暂停期间攒下的输出一次补上。
        PtyWebHost.active?.setPaused(false)
        // 从 AI 返回时记录「终端」；AI 页前台时由 OpenCodeHeader 记录「ai」，
        // 因此用户直接划掉 App 也能在下次冷启动回到 AI。
        AppStateStore.setLastScreen(this, "terminal")
        // AI 网页写过的 oc_watch（含远程服务器）在进程重启后仍保留；
        // 让 :ai 进程把 watcher 拉起来。本进程不加载 AAR，避免终端会话被
        // AI 的 WebView/MediaPlayer 初始化拖住。
        AiIntents.watch(this)
    }

    override fun onPause() {
        super.onPause()
        // 终端页不可见（尤其切到前台 AI）时停止向它的 WebView 推帧：
        // 分进程后已不会抢 AI 的主线程，但后台持续渲染仍会白耗 CPU/电量。
        PtyWebHost.active?.setPaused(true)
    }

    /**
     * 把左右两条 40dp 的竖条从系统「边缘返回手势」里排除掉，
     * 否则从屏幕最左边起手的右滑会被系统当成返回，抽屉滑不开。
     */
    private fun applyEdgeGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val decor = window.decorView
        decor.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val strip = (40 * resources.displayMetrics.density).toInt().coerceAtMost(w / 4)
            runCatching {
                v.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, strip, h),
                    android.graphics.Rect(w - strip, 0, w, h)
                )
            }
        }
    }

    @Composable
    private fun Screen() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        // 设置页在 :main 进程写 global.json，本进程只缓存自己的快照。
        // 每次回到前台/设置页返回都要重读，否则改字号、改主题都「不生效」。
        // 磁盘读一律走 IO 线程：初值为空，首帧不再同步读盘。
        var settings by remember { mutableStateOf<GlobalSettings?>(null) }
        ZlObserveResume {
            GlobalSettings.invalidate()
            scope.launch {
                settings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GlobalSettings.load(ctx)
                }
            }
        }
        LaunchedEffect(Unit) {
            settings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                GlobalSettings.load(ctx)
            }
        }
        // ContainerRepo 构造函数只存 ctx，不读盘；读文件全部在 IO 线程上做。
        val repo = remember { ContainerRepo(ctx) }

        // 容器任务在主进程执行，终端进程只能读它写出的跨进程状态文件。
        // 任务进度写盘被主进程合并到 150ms 一次，这里 800ms 轮询足够。
        var taskStates by remember { mutableStateOf<List<ContainerTasks.State>>(emptyList()) }
        LaunchedEffect(Unit) {
            while (true) {
                val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    TaskStateStore.read(ctx)
                }
                if (next != taskStates) taskStates = next
                kotlinx.coroutines.delay(800)
            }
        }

        var containers by remember { mutableStateOf<List<ContainerConfig>>(emptyList()) }
        var active by remember { mutableStateOf<ContainerConfig?>(null) }

        LaunchedEffect(Unit) {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.all() to repo.active()
            }
            containers = loaded.first
            active = loaded.second
        }

        // 默认容器是在 :main 进程（容器管理页）改的，而本进程是 :terminal：
        // 只能轮询 containers.json（每次调用都读文件并带文件锁，跨进程可见）。
        // 发现 active 变了就把本页的会话/顶栏切过去。
        LaunchedEffect(Unit) {
            var last = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { repo.active() }.getOrNull()
            }
            while (true) {
                kotlinx.coroutines.delay(2000)
                // rootfs 下载/初始化完成只改同一条配置，uuid 不变；必须比较
                // rootfsReady/initialized，否则终端会一直拿着启动时的旧配置显示
                // 「无 rootfs」，要重启 App 才恢复。
                val (cfg, all) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val c = runCatching { repo.active() }.getOrNull()
                    c to if (c == null) emptyList() else {
                        runCatching { repo.all() }.getOrDefault(emptyList())
                    }
                }
                if (cfg == null) continue
                if (last != null &&
                    cfg.uuid == last.uuid &&
                    cfg.label == last.label &&
                    cfg.rootfsReady == last.rootfsReady &&
                    cfg.initialized == last.initialized
                ) {
                    continue
                }
                last = cfg
                containers = all
                active = cfg
            }
        }

        // 定期回收已被 kill 的会话（僵尸进程的 /proc 仍在，必须看 stat 状态位）
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                SessionRegistry.reapDeadPids()
            }
        }

        // 容器重启/删除发生在 :main 进程，跨进程不能直接调用 SessionRegistry。
        // 主进程通过 SessionSignals 落文件，本进程消费后重建/移除对应会话。
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1500)
                val restarts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SessionSignals.consumeRestarts(ctx)
                }
                val removes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SessionSignals.consumeRemoves(ctx)
                }
                restarts.forEach { uuid ->
                    runCatching { SessionRegistry.removeContainer(uuid, restart = true) }
                }
                removes.forEach { uuid ->
                    runCatching { SessionRegistry.removeContainer(uuid, restart = false) }
                }
            }
        }

        val sessions by SessionRegistry.list.collectAsState()

        var currentSid by remember { mutableStateOf<String?>(null) }
        // 已经调用过 zlTerm.create 的 sid；WebView 重建后要清空重挂。
        val jsSids = remember { mutableSetOf<String>() }
        var webView by remember { mutableStateOf<WebView?>(null) }
        var host by remember { mutableStateOf<PtyWebHost?>(null) }
        var ready by remember { mutableStateOf(false) }
        var pendingJs by remember { mutableStateOf(listOf<String>()) }

        var renameTarget by remember { mutableStateOf<SessionRegistry.Info?>(null) }
        var renameText by remember { mutableStateOf("") }

        val drawerState = rememberDrawerState(DrawerValue.Closed)

        // 手势阈值一律按 dp 换算：裸 px 在不同密度下松紧不一致。
        val density = LocalDensity.current
        val drawerDragThreshold = with(density) { 40.dp.toPx() }
        val edgeDragThreshold = with(density) { 36.dp.toPx() }

        // 右侧文件抽屉。与左侧会话抽屉互斥：同一时刻只允许一个开着。
        var filesOpen by remember { mutableStateOf(false) }

        // 抽屉一打开就收键盘：终端是 WebView，键盘留着会把抽屉顶掉半屏。
        // 只在「打开」时收，关闭后不自动再弹（终端自己点一下仍可出键盘）。
        val focusManager = LocalFocusManager.current
        val imeController = remember {
            WindowCompat.getInsetsController(window, window.decorView)
        }
        LaunchedEffect(drawerState.isOpen, filesOpen) {
            if (drawerState.isOpen || filesOpen) {
                runCatching { imeController.hide(WindowInsetsCompat.Type.ime()) }
                focusManager.clearFocus()
            }
        }

        // 返回键：先关抽屉，都没有时「再按一次退出」（2s 内两按才 finish）。
        val lastBackAt = remember { longArrayOf(0L) }
        BackHandler {
            when {
                filesOpen -> filesOpen = false
                drawerState.isOpen -> scope.launch { drawerState.close() }
                else -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBackAt[0] < 2000L) {
                        finish()
                    } else {
                        lastBackAt[0] = now
                        Toast.makeText(ctx, ctx.getString(R.string.terminal_exit_again), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 终端的横滑手势 → 开合抽屉（边缘被系统返回手势占用，只能从区域内部滑）
        DisposableEffect(Unit) {
            PtyWebBridge.swipeHandler = { dir ->
                if (dir == "right") {
                    if (filesOpen) filesOpen = false
                    else scope.launch { drawerState.open() }
                } else {
                    // 左滑只负责「关左抽屉」，不再顺手把右抽屉打开（否则关一个开一个）。
                    if (drawerState.isOpen) scope.launch { drawerState.close() }
                    else filesOpen = true
                }
            }
            onDispose { PtyWebBridge.swipeHandler = null }
        }

        fun runJs(script: String) {
            val wv = webView
            if (wv == null || !ready) {
                pendingJs = pendingJs + script
            } else {
                wv.evaluateJavascript(script, null)
            }
        }

        // 输入法弹起后把终端拉回最新一行：键盘让位会让 WebView 变矮，JS 侧的
        // fit() 在 180ms 后重排并按「保持原视口」补 scroll，用户翻在历史里就
        // 会停在中间、输入时看不到提示符。这里等重排结束再滚到底。
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        LaunchedEffect(imeVisible) {
            if (!imeVisible) return@LaunchedEffect
            kotlinx.coroutines.delay(320)
            runJs("window.zlTerm && zlTerm.scrollToBottom && zlTerm.scrollToBottom()")
        }

        // JS 侧视图与 Kotlin 侧会话是两份状态：WebView 一旦重建（进程重启、
        // 页面重载），JS 里就没有任何 term-host，必须重新挂载，否则终端空白。
        fun mountSession(sid: String, uuid: String) {
            if (jsSids.add(sid)) {
                runJs("window.zlTerm && zlTerm.create('$sid','$uuid',80,24)")
            }
            runJs("window.zlTerm && zlTerm.show('$sid')")
        }

        fun createSession(cfg: ContainerConfig?, attempt: Int = 0) {
            if (cfg == null) return
            // 同容器里已经退出的旧会话不再保留：切后台/WebView 重载后如果反复
            // 新建，它们会在抽屉里越积越多，并且全部显示成「已退出」。
            SessionRegistry.visible()
                .filter { it.containerUuid == cfg.uuid && !it.running }
                .forEach { stale ->
                    jsSids.remove(stale.sid)
                    runJs("window.zlTerm && zlTerm.dispose('${stale.sid}')")
                    host?.discard(stale.sid)
                    SessionRegistry.remove(stale.sid, kill = false)
                }
            val session = SessionRegistry.createForContainer(
                ctx, cfg.uuid, cfg.label, cols = 80, rows = 24
            )
            android.util.Log.i(
                "ZlSession",
                "createSession ${cfg.uuid} -> ${session?.sid ?: "null"} attempt=$attempt running=${session?.running}"
            )
            if (session == null || !session.running) {
                // 极少数情况下新 shell 启动瞬间就退出（旧 PTY 释放/系统回收）。
                // 不要把它留在抽屉里当「已退出」，短暂延迟后重建一次。
                if (session != null) SessionRegistry.remove(session.sid, kill = false)
                if (attempt < 1) {
                    scope.launch {
                        kotlinx.coroutines.delay(300)
                        createSession(cfg, attempt + 1)
                    }
                }
                return
            }
            currentSid = session.sid
            mountSession(session.sid, cfg.uuid)
            if (attempt == 0) {
                // 启动成功但几十毫秒后被系统杀掉的场景：延迟自检一次。
                scope.launch {
                    kotlinx.coroutines.delay(700)
                    val latest = SessionRegistry.get(session.sid) ?: return@launch
                    if (!latest.running && currentSid == session.sid) {
                        android.util.Log.w("ZlSession", "new session died early sid=${session.sid}, retry once")
                        SessionRegistry.remove(session.sid, kill = false)
                        createSession(cfg, attempt + 1)
                    }
                }
            }
        }

        fun showSession(sid: String) {
            val session = SessionRegistry.get(sid)
            if (session != null && !session.running) {
                // 用户 exit 过的会话：复用同一个 sid 重开 shell。旧 JS 视图里
                // 还留着「已退出」，先 dispose；会话重启后刷新注册表，抽屉里
                // 会按新 pid 重新发布运行状态，而不是一直显示「已退出」。
                jsSids.remove(sid)
                runJs("window.zlTerm && zlTerm.dispose('$sid')")
                host?.discard(sid)
                runCatching { session.kill() }
                if (!session.start(ctx, 80, 24)) {
                    SessionRegistry.remove(sid, kill = false)
                    currentSid = null
                    return
                }
                SessionRegistry.refresh()
                android.util.Log.i("ZlSession", "restart exited sid=$sid pid=${session.pid}")
            }
            currentSid = sid
            val uuid = SessionRegistry.get(sid)?.containerUuid ?: active?.uuid
            if (uuid != null) mountSession(sid, uuid) else {
                runJs("window.zlTerm && zlTerm.show('$sid')")
            }
        }

        // 文件抽屉「在终端打开」：切到本容器会话（没有就新建），把 cd 命令打进 shell。
        // physical=true 时用 `cd -P`，落到符号链接指向的物理目录（目录链接专用）。
        fun openFolderInTerminal(path: String, cfg: ContainerConfig, physical: Boolean = false) {
            // 单引号包裹并转义内部单引号；cd -- 防止目录名以 '-' 开头被当成选项。
            fun shq(p: String): String = "'" + p.replace("'", "'\\''") + "'"
            var sid = currentSid
            if (sid == null || SessionRegistry.get(sid)?.containerUuid != cfg.uuid) {
                sid = SessionRegistry.visible().firstOrNull { it.containerUuid == cfg.uuid }?.sid
            }
            if (sid == null) {
                sid = SessionRegistry.createForContainer(
                    ctx, cfg.uuid, cfg.label, cols = 80, rows = 24
                )?.sid
            }
            if (sid != null) {
                if (currentSid != sid) showSession(sid) else mountSession(sid, cfg.uuid)
                val cd = if (physical) "cd -P -- " else "cd -- "
                SessionRegistry.get(sid)?.write(cd + shq(path) + "\r")
            }
        }

        // 页面重新加载后 JS 侧是空的，之前记录过的 sid 全部失效
        LaunchedEffect(ready) {
            if (ready) jsSids.clear()
        }

        // 活跃容器变化时确保有会话
        LaunchedEffect(active?.uuid, ready) {
            val cfg = active ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            val existing = SessionRegistry.visible().firstOrNull {
                it.containerUuid == cfg.uuid && it.running
            }
            if (existing != null) {
                showSession(existing.sid)
            } else {
                // 没有运行中的会话：优先复用该容器里已退出的会话（showSession
                // 会重开 shell），避免每次切回容器都多出一条「已退出」记录。
                val exited = SessionRegistry.visible().firstOrNull {
                    it.containerUuid == cfg.uuid
                }
                if (exited != null) showSession(exited.sid) else createSession(cfg)
            }
        }

        // 容器「重启」会清掉该容器的全部会话：如果重启的正是当前容器，
        // 立刻在 WebView 里重建一个 shell，否则页面会停在已退出的旧进程上。
        // 用 SessionRegistry.restartTick 而不是「sessions 里没有当前 sid」判断，
        // 避免用户手动 exit / 关闭会话时被自动拉起。
        val restartTick by SessionRegistry.restartTick.collectAsState()
        val lastRestarted by SessionRegistry.lastRestartedUuid.collectAsState()
        // 每个 tick 只能消费一次。WebView 在切后台/回前台时可能反复重载，
        // ready 每次变 true 都会重跑下面的 effect；没有这个标记就会每次再建
        // 一个 shell，抽屉里最终堆满「已退出」的旧会话。
        var handledRestartTick by remember { mutableStateOf(restartTick) }
        LaunchedEffect(restartTick, ready, lastRestarted, active?.uuid) {
            android.util.Log.i(
                "ZlSession",
                "restart effect tick=$restartTick handled=$handledRestartTick ready=$ready " +
                    "last=$lastRestarted active=${active?.uuid} sid=$currentSid"
            )
            if (!ready || restartTick == 0L) return@LaunchedEffect
            if (handledRestartTick == restartTick) return@LaunchedEffect
            val cfg = active ?: return@LaunchedEffect
            if (lastRestarted != cfg.uuid) return@LaunchedEffect
            handledRestartTick = restartTick
            // 只处理被重启容器自己的会话；当前显示别的容器的会话时不要误删。
            val dead = currentSid?.takeIf {
                SessionRegistry.get(it)?.containerUuid == cfg.uuid
            }
            if (dead != null) {
                jsSids.remove(dead)
                runJs("window.zlTerm && zlTerm.dispose('$dead')")
                host?.discard(dead)
                SessionRegistry.remove(dead, kill = false)
            }
            currentSid = null
            createSession(cfg)
        }

        // 字号/主题单独一条 effect：只要设置变了就推给 WebView。
        // 不能塞进上面那条 —— 那条会 createSession，重跑会把会话重建一遍。
        val st = settings
        LaunchedEffect(ready, st?.fontScale, st?.theme) {
            if (!ready || st == null) return@LaunchedEffect
            val px = (13 * st.fontScale).roundToInt().coerceIn(10, 26)
            runJs("window.zlTerm && zlTerm.setFontSize($px)")
            runJs("window.zlTerm && zlTerm.setTheme('${st.theme}')")
        }

        // 一次性侧滑栏引导（写进 global.json 后不再出现）
        var guideSeen by remember { mutableStateOf(GlobalSettings.load(ctx).drawerGuideSeen) }

        ModalNavigationDrawer(
            drawerState = drawerState,
            // 关掉系统手势：Material 的抽屉拖拽会在整屏范围抢横滑，导致终端
            // 里稍微斜一点的拖动都被判成“开抽屉”。改成下面 16dp 边缘热区。
            gesturesEnabled = false,
            // 面板外的暗色遮罩：点一下即可关闭（见下方 backdrop 说明）。
            scrimColor = Color.Black.copy(alpha = 0.45f),
            drawerContent = {
                // 面板 + 面板外的透明点击层。M3 的 scrim 在 gesturesEnabled = false
                // 时 onClose 是空操作（点面板外不关），所以这里自己接一层。
                Row(modifier = Modifier.fillMaxSize()) {
                ModalDrawerSheet(
                    // 不占满全屏：留出右侧一条，露出下面的终端，点击可关闭。
                    modifier = Modifier
                        .width(304.dp)
                        // 面板本体左滑关闭（遮罩区另有一份同样的手势，合起来
                        // 覆盖整屏：抽屉一打开，从屏幕任意位置左滑都能关）。
                        .drawerSwipeClose(drawerDragThreshold, leftward = true) {
                            scope.launch { drawerState.close() }
                        },
                    drawerContainerColor = ZlSurface
                ) {
                    SessionDrawer(
                        containers = containers,
                        sessions = sessions,
                        activeUuid = active?.uuid,
                        currentSid = currentSid,
                        onPick = { sid ->
                            showSession(sid)
                            scope.launch { drawerState.close() }
                        },
                        onNew = { cfg ->
                            // 会话建在用户点击的那个容器下，但不改动全局 active
                            // 容器（否则顶栏与终端画面会被一起切走）。
                            createSession(cfg)
                            scope.launch { drawerState.close() }
                        },
                        onClose = { sid ->
                            SessionRegistry.remove(sid)
                            jsSids.remove(sid)
                            runJs("window.zlTerm && zlTerm.dispose('$sid')")
                            if (currentSid == sid) currentSid = null
                        },
                        onCloseAll = {
                            SessionRegistry.removeAllVisible()
                            jsSids.clear()
                            runJs("window.zlTerm && zlTerm.disposeAll && zlTerm.disposeAll()")
                            currentSid = null
                        },
                        onRename = { info ->
                            renameTarget = info
                            renameText = info.title
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 全屏滑动关闭：遮罩区（面板以外的所有位置）左滑也能关。
                        // 手势层在面板之外，不盖 WebView —— 抽屉关闭时本
                        // Composable 不参与组合，终端照常可点。
                        .drawerSwipeClose(drawerDragThreshold, leftward = true) {
                            scope.launch { drawerState.close() }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { scope.launch { drawerState.close() } }
                )
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ZlBg)
                    // 键盘让位由这里唯一负责：Android 15（targetSdk 35）强制
                    // edge-to-edge 后 adjustResize 不再缩窗口，只能靠 IME insets。
                    // 本 Column 变矮 → WebView 真实变矮 → HTML 的 keybar 跟着
                    // viewport 底边上移；HTML 只预留 keybar 自身高度（--zl-kb-h），
                    // 不再额外让位，所以不会压缩两次。
                    // 键盘让位不再放在根布局：imePadding()/safeDrawingPadding() 用的都是
                    // 会逐帧插值的 IME insets，键盘动画期间整棵 Column 每帧都换高度，
                    // 里面的 AndroidView(WebView) 就每帧被 relayout + 触发 xterm resize，
                    // 这是「弹键盘必卡」的根因。这里先只按「系统栏 + 刘海」让位（把 ime
                    // 从 safeDrawing 里减掉），IME 让位交给下面只包 WebView 的容器去做，
                    // 并且用非动画的 imeAnimationTarget（动画开始的瞬间就跳到终值），
                    // 一次键盘开合 = 一次 relayout，而不是几十次。
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .exclude(WindowInsets.ime)
                            // 顶部交给 ZlTopBar 自己的 statusBarsPadding()：
                            // 这样即使将来根布局换成别的 inset 组合，顶栏也始终
                            // 落在状态栏下方，不会被系统时间/信号图标压住。
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    )
            ) {
                // 顶栏跟着「当前会话所属容器」走，而不是全局 active：在抽屉里
                // 新建别的容器的会话后，标题与副标题必须一起切（与文件抽屉同口径）。
                val topCfg = topContainer(active, containers, sessions, currentSid)
                // 只有本地 opencode serve 真正监听端口后才变绿，默认灰色。
                // 每轮都重新读容器配置：进入 AI 后 ensurePort 才会分配端口，
                // 只按首帧的 opencodePort 轮询会永远停在 0，按钮一直不绿。
                // AI 按钮永远对应「全局当前容器」（点击也是 launchFromActive）。
                // 抽屉里打开别的容器的会话只改 topCfg，不能让按钮状态跟着变。
                val aiReady = androidx.compose.runtime.produceState(
                    initialValue = false,
                    active?.uuid
                ) {
                    val uuid = active?.uuid
                    while (true) {
                        val port = if (uuid == null) 0 else kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO
                        ) {
                            runCatching { repo.find(uuid)?.opencodePort ?: 0 }.getOrDefault(0)
                        }
                        value = port > 0 && kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO
                        ) {
                            AiStatus.ping(port)
                        }
                        kotlinx.coroutines.delay(3_000)
                    }
                }.value
                ZlTerminalTopBar(
                    title = topCfg?.label ?: getString(R.string.app_name),
                    subtitle = buildSubtitle(active, containers, sessions, currentSid),
                    actions = {
                        Surface(
                            onClick = {
                                startActivity(Intent(ctx, ContainerListActivity::class.java))
                            },
                            shape = RoundedCornerShape(50),
                            color = ZlSurface,
                            border = BorderStroke(1.dp, ZlBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.terminal_containers),
                                    style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ZlMuted
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            onClick = { AiIntents.launchActive(this@TerminalActivity) },
                            shape = RoundedCornerShape(50),
                            color = ZlSurface,
                            border = BorderStroke(
                                1.dp,
                                if (aiReady) androidx.compose.ui.graphics.Color(0x663FB950) else ZlBorder
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(
                                            if (aiReady) androidx.compose.ui.graphics.Color(0xFF3FB950)
                                            else androidx.compose.ui.graphics.Color(0xFF8B949E),
                                            CircleShape
                                        )
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "AI",
                                    style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (aiReady) {
                                        androidx.compose.ui.graphics.Color(0xFF3FB950)
                                    } else {
                                        ZlMuted
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            onClick = {
                                // 终端页的齿轮 = 全局设置；单容器设置从容器列表进。
                                startActivity(
                                    Intent(ctx, com.zerolinux.app.ui.main.SettingsActivity::class.java)
                                )
                            },
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = ZlSurface,
                            border = BorderStroke(1.dp, ZlBorder)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = ZlMuted
                                )
                            }
                        }
                    }
                )

                TaskBanners(taskStates)

                val cfg = active
                if (cfg == null) {
                    EmptyState()
                } else if (!cfg.rootfsReady) {
                    NoticeCard(stringResource(R.string.terminal_no_rootfs))
                } else {
                    // 终端只有 xterm.js 一套渲染器（原生选项已移除）
                    // 只有这块（紧贴 WebView 的容器）跟着 IME 让位，而且只吃「目标值」：
                    // 键盘弹出/收起各跳一次，不参与逐帧动画。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.imeAnimationTarget.only(WindowInsetsSides.Bottom)
                            )
                    ) {
                        // 显式 key：这个 WebView 必须是整棵树里最稳定的节点。
                        // 顶层 Column 的兄弟节点会随 filesOpen / TaskBanner / 引导层
                        // 增删，没有 key 时 Compose 只能按位置推断身份，一旦位置漂移
                        // 就会销毁 AndroidView 再重新 factory —— 表现就是终端画面
                        // 整个被重建（回滚缓冲丢失、回到初始提示符）。
                        androidx.compose.runtime.key("zl-terminal-webview") {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { c ->
                                    buildWebView(
                                        ctx = c,
                                        onHost = { h -> host = h },
                                        onView = { wv -> webView = wv },
                                        onPageStart = {
                                            // 页面开始重新加载 = JS 侧的 term-host 全部消失。
                                            // 必须把 Kotlin 侧的挂载记账一起清掉：否则
                                            // onReady 时 ready 仍是 true（状态没变化），
                                            // LaunchedEffect 不会重跑，jsSids 也永远不会清空，
                                            // 终端就永久空白了。
                                            ready = false
                                            jsSids.clear()
                                        },
                                        onReady = { ready = true },
                                        colsProvider = { 80 to 24 }
                                    )
                                }
                            )
                        }
                    }
                }

            }

            // 左边缘热区：Android 手势导航的左边缘是「返回」，16dp 时触摸
            // 会被系统吃掉，所以加宽到 28dp 并向系统申请手势排除区。
            // 打开态也保留（关闭主路径是面板自身的左拖；这里只作为补充，
            // 打开时多半被 sheet/scrim 盖住收不到事件，不作依赖）。
            if (!filesOpen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(16.dp)
                        // 顶栏高度约 56dp + 状态栏，热区从顶栏下方开始，
                        // 否则 40dp 全高热区会盖住顶栏最右侧的齿轮按钮。
                        .systemGestureExclusion()
                        .padding(top = 72.dp)
                        .pointerInput(edgeDragThreshold) {
                            var acc = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f },
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    acc += drag
                                },
                                onDragEnd = {
                                    // 松手才开合：拖动中只累加，避免一过阈值抽屉就抢走
                                    // 事件，手感变成「拖到一半自己弹」。
                                    if (acc > edgeDragThreshold) {
                                        scope.launch { drawerState.open() }
                                    } else if (acc < -edgeDragThreshold) {
                                        scope.launch { drawerState.close() }
                                    }
                                    acc = 0f
                                }
                            )
                        }
                )
            }

            // 右边缘热区：同样加宽并申请手势排除区
            if (!filesOpen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(16.dp)
                        .systemGestureExclusion()
                        .padding(top = 72.dp)
                        .pointerInput(edgeDragThreshold) {
                            var acc = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f },
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    acc += drag
                                },
                                onDragEnd = {
                                    // 松手才开：拖动中只累加，避免一过阈值抽屉就抢走事件。
                                    if (acc < -edgeDragThreshold) filesOpen = true
                                    acc = 0f
                                }
                            )
                        }
                )
            }

            // 文件抽屉跟随「当前选中会话」所属容器，而不是全局 active：
            // 会话可以建在别的容器下（onNew 不改 active），此时浏览的必须是
            // 该会话的 rootfs；没有会话信息时回退到 active。
            val sessionContainerUuid =
                sessions.firstOrNull { it.sid == currentSid }?.containerUuid
            val fileCfg = containers.firstOrNull {
                it.uuid == (sessionContainerUuid ?: active?.uuid)
            } ?: active
            androidx.compose.animation.AnimatedVisibility(
                visible = filesOpen,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(160)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(160)
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        // 右抽屉同样支持「打开后整屏右滑关闭」：这块遮罩占满
                        // 除面板以外的全部区域，向右滑超过阈值即关闭。
                        .drawerSwipeClose(drawerDragThreshold, leftward = false) {
                            filesOpen = false
                        }
                        .clickable { filesOpen = false }
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = filesOpen && fileCfg != null,
                enter = androidx.compose.animation.slideInHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(220),
                    initialOffsetX = { it }
                ),
                exit = androidx.compose.animation.slideOutHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(180),
                    targetOffsetX = { it }
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    // 宽度只在这里定一次：FileDrawer 内部不再自己收窄，
                    // 否则两层 fillMaxWidth 叠加会让抽屉悬在屏幕中间。
                    .fillMaxWidth(0.92f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // 反向（向右）滑关闭，跟左抽屉对称。
                            var acc = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f },
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    acc += drag
                                    if (acc > 40f) {
                                        acc = 0f
                                        filesOpen = false
                                    }
                                }
                            )
                        }
                ) {
                    FileDrawer(
                        ctx = ctx,
                        uuid = fileCfg!!.uuid,
                        label = fileCfg.label,
                        onOpenTerminal = { path, physical ->
                            openFolderInTerminal(path, fileCfg, physical)
                            filesOpen = false
                        },
                        onClose = { filesOpen = false }
                    )
                }
            }

            // 一次性侧滑栏引导：首次进入终端页时提示左右两条边缘热区。
            if (!guideSeen) {
                DrawerGuideOverlay(onDismiss = {
                    guideSeen = true
                    GlobalSettings.update(ctx) { it.copy(drawerGuideSeen = true) }
                })
            }
            }
        }

        // WebView 就绪后把排队的 JS 一次性发出去
        LaunchedEffect(ready) {
            if (!ready) return@LaunchedEffect
            val queued = pendingJs
            pendingJs = emptyList()
            val wv = webView ?: return@LaunchedEffect
            queued.forEach { runCatching { wv.evaluateJavascript(it, null) } }
        }

        DisposableEffect(Unit) {
            onDispose {
                runCatching { host?.detach() }
                runCatching { webView?.destroy() }
                webView = null
                host = null
                ready = false
            }
        }

        renameTarget?.let { info ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text(stringResource(R.string.terminal_rename)) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        SessionRegistry.rename(info.sid, renameText.trim().ifBlank { info.title })
                        renameTarget = null
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun DrawerGuideOverlay(onDismiss: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .background(
                        ZlSurface,
                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    )
                    // 吞掉卡片内的点击，避免点在卡片上也把引导关掉
                    .clickable { }
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.drawer_guide_title),
                    style = ZlMonoBody.copy(fontWeight = FontWeight.Bold),
                    color = ZlFg
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "→",
                        style = ZlMonoBody.copy(fontWeight = FontWeight.Bold),
                        color = ZlAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.drawer_guide_sessions),
                        style = ZlMonoSmall,
                        color = ZlMuted
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "←",
                        style = ZlMonoBody.copy(fontWeight = FontWeight.Bold),
                        color = ZlAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.drawer_guide_files),
                        style = ZlMonoSmall,
                        color = ZlMuted
                    )
                }
                Spacer(Modifier.height(18.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(
                        text = stringResource(R.string.drawer_guide_start),
                        style = ZlMonoBody.copy(fontWeight = FontWeight.Bold),
                        color = ZlAccent
                    )
                }
            }
        }
    }

    /**
     * 顶栏用的紧凑容量格式：1080 宽顶栏放得下 distro + 存储。
     * fmtBytes 的 "1.1 GB" 太长，这里压成 "1.1G" / "39M"。
     */
    private object CompactBytes {
        fun of(bytes: Long): String = when {
            bytes <= 0L -> "0"
            bytes >= 1024L * 1024 * 1024 ->
                String.format(java.util.Locale.US, "%.1fG", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 ->
                String.format(java.util.Locale.US, "%dM", bytes / (1024L * 1024))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%dK", bytes / 1024L)
            else -> bytes.toString()
        }
    }

    /**
     * 抽屉的「横滑关闭」手势。
     *
     * leftward = true 向左累计超过 threshold 才回调 onClose，false 时向右。
     * 只有累计位移超过 8px 才开始 consume()，短滑/竖滑/点击都不会被抢走事件。
     * M3 的 drawer 手势在 gesturesEnabled = false 时是空的，所以面板与遮罩
     * 各挂一份，合起来就是「打开后整屏任意位置滑动都能关」。
     */
    private fun Modifier.drawerSwipeClose(
        threshold: Float,
        leftward: Boolean,
        onClose: () -> Unit
    ): Modifier = this.pointerInput(threshold, leftward) {
        var acc = 0f
        var armed = false
        detectHorizontalDragGestures(
            onDragStart = {
                acc = 0f
                armed = false
            },
            onHorizontalDrag = { change, drag ->
                acc += drag
                if (!armed && kotlin.math.abs(acc) > 8f) armed = true
                if (armed) change.consume()
            },
            onDragEnd = {
                val hit = if (leftward) acc < -threshold else acc > threshold
                acc = 0f
                armed = false
                if (hit) onClose()
            },
            onDragCancel = {
                acc = 0f
                armed = false
            }
        )
    }

    /**
     * 顶栏应该显示的容器。
     *
     * 口径与文件抽屉一致：优先「当前会话所属容器」
     * （`sessions.firstOrNull { it.sid == currentSid }?.containerUuid`），
     * 只有没有会话时才退回全局 active。否则在抽屉里切到别的容器的会话后，
     * 顶栏仍停在旧容器上（就是"切换会话后顶栏不跟着切"）。
     */
    private fun topContainer(
        active: ContainerConfig?,
        containers: List<ContainerConfig>,
        sessions: List<SessionRegistry.Info>,
        currentSid: String?
    ): ContainerConfig? {
        val uuid = sessions.firstOrNull { it.sid == currentSid }?.containerUuid
        return uuid?.let { u -> containers.firstOrNull { it.uuid == u } } ?: active
    }

    @androidx.compose.runtime.Composable
    private fun buildSubtitle(
        active: ContainerConfig?,
        containers: List<ContainerConfig>,
        sessions: List<SessionRegistry.Info>,
        currentSid: String?
    ): String {
        // 会话所属容器优先（topContainer），保证副标题与顶栏标题、终端画面同源。
        val cfg = topContainer(active, containers, sessions, currentSid) ?: return "no container"
        // 只显示 rootfs 存储占用；运行内存相关信息不再出现在 UI。
        val rootfs = com.zerolinux.app.core.AppPaths.containerRootfs(this, cfg.uuid)
        val activity = this
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val usage = androidx.compose.runtime.produceState(
            initialValue = "",
            cfg.uuid
        ) {
            // 首帧只读缓存：终端页打开速度不能被几十万文件的体积统计拖慢。
            // 缓存由容器列表页负责补算，这里永远不扫盘。
            while (true) {
                // Activity 退到后台（未销毁）时不再刷新副标题。
                while (!owner.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED
                    )
                ) {
                    kotlinx.coroutines.delay(1_500)
                }
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.zerolinux.app.core.SysInfo.peekDirBytes(rootfs)
                }
                if (bytes != null) {
                    val next = activity.getString(
                        R.string.terminal_storage,
                        com.zerolinux.app.ui.terminal.TerminalActivity.CompactBytes.of(bytes)
                    )
                    // 只有数值真的变了才写 state：否则白重组一次顶栏。
                    if (next != value) value = next
                }
                kotlinx.coroutines.delay(30_000)
            }
        }.value
        val sep = stringResource(R.string.terminal_sep)
        return listOf(distroName(activity, cfg.distro), usage.ifBlank { "…" })
            .joinToString(sep)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        ctx: android.content.Context,
        onHost: (PtyWebHost) -> Unit,
        onView: (WebView) -> Unit,
        onPageStart: () -> Unit,
        onReady: () -> Unit,
        colsProvider: () -> Pair<Int, Int>
    ): WebView {
        val wv = WebView(ctx)
        // 终端页的原生 IME 兜底脚本：只在输入框真正拿到焦点时才叫原生弹键盘
        // （避免变成「一碰终端就弹键盘」）；重复注入用 __zlImeHooked 去重。
        val imeHookJs = """
            (function () {
              if (window.__zlImeHooked) return;
              window.__zlImeHooked = 1;
              // 去抖 + 去环：WebView 的焦点会在 xterm 的 textarea / screen 之间抖，
              // focusin 一旦跟着抖就会把 showIme() 打成连环调用（每次 requestFocus +
              // showSoftInput 都要走一遍 IME 与布局），键盘动画期间尤其明显。
              // 350ms 窗口内只认第一次；原生侧还有一层「IME 已贴在这个 WebView 上就不再
              // 调用」的判断（PtyWebBridge.showIme）。
              document.addEventListener('focusin', function (e) {
                var t = e && e.target;
                if (!t) return;
                var n = (t.tagName || '').toUpperCase();
                if (n !== 'TEXTAREA' && n !== 'INPUT') return;
                // 长按选词/扩选期间绝不主动弹 IME：焦点在选区手势里可能被
                // WebView 挪动，这里再兜一层，保证长按不改输入法状态。
                // __zlSelectGesture 覆盖整段手势（pointerdown→抬起后 350ms），
                // __zlLongPress 只覆盖长按计时器触发之后的那一段。
                if (window.__zlLongPress || window.__zlSelectGesture) return;
                var now = Date.now();
                if (window.__zlImeAt && now - window.__zlImeAt < 350) return;
                window.__zlImeAt = now;
                try {
                  window.zlPty && window.zlPty.showIme && window.zlPty.showIme();
                } catch (err) {}
              }, true);
            })();
        """.trimIndent()
        val webHost = PtyWebHost(ctx, wv)
        onHost(webHost)
        onView(wv)

        wv.setBackgroundColor(0xFF0B0C0F.toInt())
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        // 触摸就能拿到焦点：否则 Compose 容器把焦点留在别处，WebView 内的
        // textarea 再 focus 也不会让系统弹 IME（需要配合 zlPty.showIme 兜底）。
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
            mediaPlaybackRequiresUserGesture = true
        }
        wv.addJavascriptInterface(
            PtyWebBridge(webHost, ctx, colsProvider),
            "zlPty"
        )
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.println(
                    when (msg.messageLevel()) {
                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> android.util.Log.ERROR
                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> android.util.Log.WARN
                        else -> android.util.Log.INFO
                    },
                    "ZlTermWeb",
                    "${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}"
                )
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            // 页面重载（渲染进程被杀后重试、或任何 loadUrl 再次进入）时先清账。
            override fun onPageStarted(
                view: WebView,
                url: String,
                favicon: android.graphics.Bitmap?
            ) {
                android.util.Log.i("ZlTermWeb", "onPageStarted $url")
                onPageStart()
            }

            override fun onPageFinished(view: WebView, url: String) {
                android.util.Log.i("ZlTermWeb", "onPageFinished $url")
                webHost.attach()
                // 原生补丁：xterm 的隐藏 textarea 拿到焦点时，页面内 focus() 不一定能让
                // Android 弹 IME（WebView 没有窗口焦点时也会静默失败），这里在页面里加一个
                // focusin 监听，只在输入框真的获得焦点时才请原生弹一次键盘。
                view.evaluateJavascript(imeHookJs, null)
                // 空态描述在 HTML 里写死为中文，这里按当前语言覆盖一次；
                // Activity 重建 / 页面重载都会再走 onPageFinished，因此无需额外监听。
                val emptyDesc = getString(R.string.terminal_empty_shell_desc)
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                view.evaluateJavascript(
                    "(function(){var e=document.querySelector('#empty .empty-desc');" +
                        "if(e){e.textContent='$emptyDesc';}})()",
                    null
                )
                // 等 xterm 初始化完再放行
                view.postDelayed({
                    view.evaluateJavascript("window.zlTerm && zlTerm.ready()") { value ->
                        if (value == "true") onReady()
                    }
                }, 60)
            }
        }
        wv.loadUrl("file:///android_asset/www/terminal/index.html")
        return wv
    }

    @Composable
    private fun SessionDrawer(
        containers: List<ContainerConfig>,
        sessions: List<SessionRegistry.Info>,
        activeUuid: String?,
        currentSid: String?,
        onPick: (String) -> Unit,
        onNew: (ContainerConfig) -> Unit,
        onClose: (String) -> Unit,
        onCloseAll: () -> Unit,
        onRename: (SessionRegistry.Info) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = stringResource(R.string.terminal_sessions),
                style = ZlMonoBody.copy(fontWeight = FontWeight.Bold),
                color = ZlFg
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                containers.forEach { cfg ->
                    item(key = "h-${cfg.uuid}") {
                        val isActive = cfg.uuid == activeUuid
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isActive) ZlRunning else ZlMuted,
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cfg.label,
                                    style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isActive) ZlFg else ZlMuted,
                                    softWrap = true
                                )
                                Text(
                                    text = distroName(LocalContext.current, cfg.distro),
                                    style = ZlMonoSmall,
                                    color = ZlMuted
                                )
                            }
                            TextButton(onClick = { onNew(cfg) }) {
                                Text("+", color = ZlAccent, style = ZlMonoSmall, fontSize = 22.sp)
                            }
                        }
                    }
                    val group = sessions.filter { it.containerUuid == cfg.uuid }
                    items(group, key = { it.sid }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { onPick(s.sid) },
                                modifier = Modifier.weight(1f)
                            ) {
                                // 上行会话名；只有已退出的会话才额外显示状态行。
                                Column {
                                    Text(
                                        text = s.title,
                                        style = ZlMonoSmall,
                                        color = if (s.sid == currentSid) ZlAccent else ZlFg,
                                        softWrap = true
                                    )
                                    if (!s.running) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.terminal_exited),
                                            style = ZlMonoSmall,
                                            color = ZlMuted
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { onRename(s) }) {
                                Text("…", style = ZlMonoSmall, color = ZlMuted)
                            }
                            TextButton(onClick = { onClose(s.sid) }) {
                                Text("×", style = ZlMonoSmall, color = ZlDanger)
                            }
                        }
                    }
                    item(key = "d-${cfg.uuid}") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(ZlBorder)
                        )
                    }
                }
            }
            TextButton(onClick = onCloseAll) {
                Text(stringResource(R.string.terminal_close_all), color = ZlDanger, style = ZlMonoSmall)
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.terminal_no_container), style = ZlMonoBody, color = ZlFg)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.terminal_empty_desc), style = ZlMonoSmall, color = ZlMuted)
        }
    }

    @Composable
    private fun NoticeCard(text: String, color: Color = ZlUnread) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text, style = ZlMonoSmall, color = color)
        }
    }

    @Composable
    private fun NativeNotice(cfg: ContainerConfig) {
        val ctx = LocalContext.current
        // ProotLauncher.isReady 会读文件系统：初值 false，异步回填，避免阻塞首帧。
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ProotLauncher.isReady(ctx)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.terminal_native_notice),
                style = ZlMonoSmall,
                color = ZlMuted
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${cfg.label} · ${cfg.uuid.take(8)}",
                style = ZlMonoSmall,
                color = ZlFg
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (ready) "proot ready" else "proot missing",
                style = ZlMonoSmall,
                color = if (ready) ZlRunning else ZlDanger
            )
        }
    }
}
