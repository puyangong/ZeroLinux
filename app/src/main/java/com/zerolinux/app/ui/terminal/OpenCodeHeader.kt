package com.zerolinux.app.ui.terminal

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.opencode.mobile.OpenCodeMobile
import com.opencode.mobile.OpenCodeMobileIme
import com.opencode.mobile.OpenCodeMobileTopBar
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerConfig
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.core.AppPaths
import com.zerolinux.app.core.SysInfo
import com.zerolinux.app.opencode.AiSound
import com.zerolinux.app.opencode.AppStateStore
import com.zerolinux.app.ui.common.ZlHostOwners
import com.zerolinux.app.ui.common.ZlTerminalTopBar
import com.zerolinux.app.ui.container.distroName
import com.zerolinux.app.ui.theme.ZeroLinuxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * OpenCode 客户端是 AAR 里的独立 Activity，无法直接嵌进终端页。
 * AAR 的 MainActivity 与 OpenCodeHeader 同在 `:ai` 进程，因此可以拿到
 * 同一个 MainActivity 实例，在它的内容区顶部加一条宿主顶栏（返回 + 容器信息），
 * 并把 AAR 自己的界面整体上移，贴紧顶栏。
 *
 * 顶栏**复用终端的 `ZlTopBar`**：AAR 的 MainActivity 是原生 Activity，
 * 这里用 `ComposeView` + `ZlHostOwners` 给它补一套 Compose owner，再把
 * 同一个 Composable 渲染进去。不再维护第二套手写 View 顶栏，尺寸、
 * 返回键位置、字号、分隔线永远和终端页一致。
 */
object OpenCodeHeader {

    private const val TAG = "zl_opencode_header"

    private val listeners = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, OpenCodeMobileIme.Listener>()
    )

    /**
     * 已挂载的宿主顶栏。
     *
     * 顶栏是 `ZlTopBar` 的同一份实现，配色也直接用它默认的终端色板，
     * 不再跟随 AAR 内部的浅色/深色设置，所以这里只保留视图与 owner。
     */
    private val bars = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, HostBar>()
    )

    private class HostBar(
        val view: ComposeView,
        val owners: ZlHostOwners
    )

    fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        AppStateStore.setLastScreen(activity, "ai")
        // 每次进入 AI 都重新落一次样式，避免旧版本 AAR 持久化的顶栏样式残留。
        applyTopBar(activity)
        // AAR 静态注册可能在进程重建/库内部重置后丢失，进入 AI 时补挂一次。
        AiSound.install(activity)
        // 从 AI 的其他页面返回也会走 resume：栏已存在时不重建。
        if (content.findViewWithTag<View>(TAG) != null) return

        val density = activity.resources.displayMetrics.density
        // Android 15/16 对 targetSdk 35+ 强制 edge-to-edge，content 实际从 y=0
        // 开始；顶栏自己用 statusBarsPadding() 让出状态栏（和终端页同一种做法）。
        val statusBar = statusBarInset(activity, content)
        val owners = ZlHostOwners(activity).apply { start() }
        val view = ComposeView(activity).apply {
            tag = TAG
            // Compose 沿 View 树找 owner：装在顶栏自己身上即可。
            owners.install(this)
            setContent {
                // 与终端页同一个主题包：字号缩放（uiScale）、字体、色板都按
                // ZeroLinuxTheme 走，否则 AI 页用的是 AAR Activity 的默认主题，
                // 一旦用户在设置里调过字号，两边标题大小就会不一致。
                ZeroLinuxTheme {
                    AiTopBar(activity = activity, onBack = { close(activity) })
                }
            }
        }
        content.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        bars[activity] = HostBar(view, owners)

        // 先给一个估算值，保证 AAR 页面首帧就有正确的让位；真实高度出来后
        // 再用测量值校正（顶栏包含状态栏 padding，量出来就是「inset 总高」）。
        OpenCodeMobile.setHeaderInsetDp(activity, statusBar / density + 57f + 2f)
        view.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            // 旋转/分屏时状态栏高度会变，跟着重新告诉 AAR 一次。
            if (height > 0 && height != oldBottom - oldTop && !activity.isDestroyed) {
                OpenCodeMobile.setHeaderInsetDp(activity, height / density + 2f)
            }
        }

        // 新版 AAR IME 接口：配置 resize；每次键盘状态/高度变化都回调宿主重绘。
        runCatching {
            OpenCodeMobileIme.configure(activity)
            OpenCodeMobileIme.redraw(activity)
            val listener = OpenCodeMobileIme.Listener { state ->
                runCatching {
                    OpenCodeMobileIme.redraw(activity)
                    if (state.isVisible()) OpenCodeMobile.redrawKeyboardLayout()
                }
            }
            OpenCodeMobileIme.addListener(listener)
            listeners[activity] = listener
        }
    }

    /**
     * AI 顶栏：只提供数据，顶栏本体就是终端页那份 [ZlTerminalTopBar]。
     *
     * 信息加载方式与终端顶栏保持一致：
     * - 容器配置每 2 秒重读一次（与终端页轮询 active 的节奏相同），改标签、
     *   切默认容器或 rootfs 就绪后顶栏自己更新，不用重进 AI；
     * - 存储占用由 [aiSubtitle] 每 5 秒重算一次，退到后台时暂停；
     * - 存储还没算完时副标题用「…」占位，与终端 `usage.ifBlank { "…" }` 相同。
     */
    @Composable
    private fun AiTopBar(
        activity: Activity,
        onBack: () -> Unit
    ) {
        val cfg by produceState<ContainerConfig?>(initialValue = null, activity) {
            while (true) {
                val next = withContext(Dispatchers.IO) {
                    runCatching {
                        val repo = ContainerRepo(activity)
                        AppStateStore.lastAiUuid(activity)
                            ?.let { repo.find(it) }
                            ?: repo.active()
                    }.getOrNull()
                }
                if (next != value) value = next
                delay(2_000)
            }
        }
        ZlTerminalTopBar(
            title = cfg?.label ?: activity.getString(R.string.app_name),
            subtitle = aiSubtitle(activity, cfg),
            onBack = onBack
        )
    }

    /**
     * 副标题：与终端页 `buildSubtitle` 同一套算法。
     *
     * 没有容器时返回 "no container"；有容器时是「发行版 · 存储」，存储还没
     * 算完用「…」占位，并且只在数值变化时写 state，避免每 5 秒白重组一次。
     */
    @Composable
    private fun aiSubtitle(activity: Activity, cfg: ContainerConfig?): String {
        val rootfs = cfg?.let { AppPaths.containerRootfs(activity, it.uuid) }
        val owner = LocalLifecycleOwner.current
        val usage by produceState(initialValue = "", cfg?.uuid) {
            if (rootfs == null) return@produceState
            // 只读缓存，绝不扫盘：AI 页和终端页共用终端进程，扫盘会拖慢输入。
            while (true) {
                // Activity 退到后台（未销毁）时不再刷新副标题。
                while (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    delay(1_500)
                }
                val bytes = withContext(Dispatchers.IO) { SysInfo.peekDirBytes(rootfs) }
                if (bytes != null) {
                    val next = activity.getString(
                        R.string.terminal_storage,
                        compactBytes(bytes)
                    )
                    if (next != value) value = next
                }
                delay(30_000)
            }
        }
        if (cfg == null) return "no container"
        return listOf(distroName(activity, cfg.distro), usage.ifBlank { "…" })
            .joinToString(activity.getString(R.string.terminal_sep))
    }

    private fun close(activity: Activity) {
        // 用 AAR 的 IME 接口先收键盘，再返回终端，避免键盘残留。
        runCatching { OpenCodeMobileIme.hide(activity) }
        AppStateStore.setLastScreen(activity, "terminal")
        activity.finish()
    }

    /**
     * 统一 AAR 所有页面顶栏的尺寸，颜色交给 AAR 主题。
     *
     * 新 AAR 的 setTopBar 会同时作用于 home/page/project/session/terminal
     * 五个顶栏，并持久化到 SharedPreferences；页面重载或进程重建后仍生效。
     *
     * 这里只写高度、内边距和分隔线，**不写死背景/前景色**：色值全部走 AAR
     * 自己的 `var(--bg)` / `var(--text)` / `var(--border)`，用户在 AI 设置里
     * 切换浅色/深色时顶栏会跟着变，而不是永远停在固定的深色。
     */
    fun applyTopBar(ctx: Context) {
        runCatching {
            OpenCodeMobile.setTopBar(
                ctx.applicationContext,
                OpenCodeMobileTopBar.builder()
                    .minHeight(56)
                    .blur(0)
                    .css(
                        "padding-left:8px;padding-right:12px;" +
                            "border-bottom:1px solid var(--border);"
                    )
                    .build()
            )
        }
    }

    /**
     * 顶栏用的紧凑容量格式，与终端页 `TerminalActivity.CompactBytes.of`
     * 保持一致：1080 宽顶栏放得下「发行版 · 存储」，不显示 "1.1 GB"。
     */
    private fun compactBytes(bytes: Long): String = when {
        bytes <= 0L -> "0"
        bytes >= 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1fG", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 ->
            String.format(java.util.Locale.US, "%dM", bytes / (1024L * 1024))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%dK", bytes / 1024L)
        else -> bytes.toString()
    }

    /** 状态栏高度（px）。优先读窗口 insets；attach 发生在 resume 时 insets
     * 偶尔还没分发，退回系统资源，保证不会算出 0。 */
    private fun statusBarInset(activity: Activity, content: View): Int {
        val fromInsets = runCatching {
            ViewCompat.getRootWindowInsets(content)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }.getOrDefault(0)
        if (fromInsets > 0) return fromInsets
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            runCatching { activity.resources.getDimensionPixelSize(id) }.getOrDefault(0)
        } else {
            0
        }
    }

    /**
     * 记录 AAR 当前页面（hash 路由）与选中的服务器。
     *
     * AI Activity 是独立 Activity，用户点返回/HOME/切后台后 WebView 可能被销毁；
     * 在 onPause 时把状态落到 SharedPreferences，下次进入直接 openPage 恢复。
     */
    fun captureNow(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        val web = findWebView(root) ?: return
        // 同步兜底：getUrl() 立即带 hash，即使点返回后 Activity 很快销毁、
        // evaluateJavascript 回调来不及执行，也能保存当前页面。
        runCatching {
            val route = web.url?.substringAfter('#', "")?.trim().orEmpty()
            if (route.isNotEmpty()) {
                AppStateStore.setLastAiPage(
                    activity,
                    route,
                    AppStateStore.lastAiServer(activity).orEmpty()
                )
            }
        }
        val js = """
            (function(){
              try {
                var server = '';
                try {
                  var raw = localStorage.getItem('opencode-mobile-app');
                  if (raw) {
                    var obj = JSON.parse(raw);
                    server = (obj && obj.state && obj.state.currentServerId) || '';
                  }
                } catch (e) {}
                return JSON.stringify({route: location.hash || '', server: server});
              } catch (e) { return ''; }
            })()
        """.trimIndent()
        activity.runOnUiThread {
            runCatching {
                web.evaluateJavascript(js) { value ->
                    // evaluateJavascript 把返回值再 JSON 编码一层：先解码成字符串，再解析对象。
                    val decoded = runCatching {
                        JSONTokener(value).nextValue() as? String
                    }.getOrNull() ?: return@evaluateJavascript
                    val obj = runCatching { JSONObject(decoded) }.getOrNull()
                        ?: return@evaluateJavascript
                    val route = obj.optString("route")
                    val server = obj.optString("server")
                    if (route.isNotBlank() || server.isNotBlank()) {
                        AppStateStore.setLastAiPage(activity, route, server)
                    }
                }
            }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Activity 销毁时解绑 IME 监听与 Compose owner，避免泄漏和重复回调。 */
    fun detach(activity: Activity) {
        bars.remove(activity)?.owners?.destroy()
        listeners.remove(activity)?.let { listener ->
            runCatching { OpenCodeMobileIme.removeListener(listener) }
        }
    }
}
