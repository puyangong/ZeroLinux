package com.zerolinux.app.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.zerolinux.app.engine.SessionRegistry
import java.io.ByteArrayOutputStream

/**
 * xterm WebView 与 `:terminal` 会话之间的桥。
 *
 * 输出方向：read thread 收到字节 -> 按 sid 攒进 ByteArrayOutputStream ->
 * 每 16ms 在主线程一次性 base64 后 evaluateJavascript（合帧，避免每 4KB 一次调用）。
 *
 * 输入方向：window.zlPty.write(id, data) 由 [PtyWebBridge] 接住。
 */
class PtyWebHost(
    private val context: Context,
    private val webView: WebView
) {

    private val main = Handler(Looper.getMainLooper())
    private val buffers = HashMap<String, ByteArrayOutputStream>()
    private var flushScheduled = false

    /**
     * 终端页不可见（例如用户去了前台 AI 模块）时置 true。
     *
     * 此时读线程仍会产出字节，但不再每 16ms 往 WebView 里 evaluateJavascript：
     * AI 与终端已经分进程，互不抢主线程；暂停主要是为了省电、省 CPU，
     * 避免后台终端持续渲染。字节只攒在缓冲里，回到终端时一次性补上。
     */
    @Volatile
    private var paused = false

    /** 暂停期间发生的会话退出，恢复时补发，避免页面一直显示运行中。 */
    private val pendingExits = HashMap<String, Int>()

    init {
        active = this
    }

    /** 暂停/恢复向 WebView 推帧；恢复时立即把攒下的输出补推一次。 */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        if (!value) {
            if (Looper.myLooper() == Looper.getMainLooper()) resume() else main.post { resume() }
        }
    }

    /** 恢复推帧：先补输出，再补发暂停期间积压的退出事件。 */
    private fun resume() {
        flush()
        val exits: Map<String, Int>
        synchronized(pendingExits) {
            if (pendingExits.isEmpty()) return
            exits = HashMap(pendingExits)
            pendingExits.clear()
        }
        exits.forEach { (sid, code) ->
            eval("window.__zlPty && window.__zlPty.onExit('${esc(sid)}',$code)")
        }
    }

    fun attach() {
        SessionRegistry.attachSink(
            onData = { sid, bytes -> enqueue(sid, bytes) },
            onExit = { sid, code -> dispatchExit(sid, code) }
        )
    }

    fun detach() {
        SessionRegistry.detachSink()
        flush()
        if (active === this) active = null
    }

    /** 丢弃某个会话尚未推给 JS 的旧输出（会话重启前调用）。 */
    fun discard(sid: String) {
        synchronized(buffers) { buffers.remove(sid) }
    }

    private fun enqueue(sid: String, bytes: ByteArray) {
        var immediate = false
        synchronized(buffers) {
            val buf = buffers.getOrPut(sid) { ByteArrayOutputStream(8192) }
            // 后台暂停期间可能攒很久：超上限就丢掉最旧的一段，避免无界增长吃掉内存。
            if (buf.size() + bytes.size > MAX_BUFFERED) buf.reset()
            buf.write(bytes)
            if (buf.size() >= 256 * 1024) immediate = true
        }
        // 页面不可见：只攒着，不排 flush，也就不会去碰 WebView 主线程。
        if (paused) return
        if (immediate) {
            if (Looper.myLooper() == Looper.getMainLooper()) flush()
            else main.post { flush() }
            return
        }
        if (!flushScheduled) {
            flushScheduled = true
            main.postDelayed({ flush() }, FRAME_MS)
        }
    }

    private fun flush() {
        val snapshot: Map<String, ByteArray>
        synchronized(buffers) {
            flushScheduled = false
            // 页面不可见时不推帧，缓冲留着等恢复。
            if (paused || buffers.isEmpty()) return
            snapshot = buffers.mapValues { it.value.toByteArray() }
            buffers.clear()
        }
        snapshot.forEach { (sid, bytes) ->
            if (bytes.isEmpty()) return@forEach
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            eval("window.__zlPty && window.__zlPty.onData('${esc(sid)}','$b64')")
        }
    }

    private fun dispatchExit(sid: String, code: Int) {
        if (paused) {
            // 暂停期间会话退出：先记下来，恢复推帧时再补发 onExit，
            // 否则页面会一直显示「运行中」。
            synchronized(pendingExits) { pendingExits[sid] = code }
            return
        }
        flush()
        eval("window.__zlPty && window.__zlPty.onExit('${esc(sid)}',$code)")
    }

    fun eval(script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { webView.evaluateJavascript(script, null) }
        } else {
            main.post { runCatching { webView.evaluateJavascript(script, null) } }
        }
    }

    fun copy(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val ok = runCatching { cm.setPrimaryClip(ClipData.newPlainText("terminal", text)) }.isSuccess
        Log.i("ZlPtyBridge", "copy ${text.length} chars ok=$ok text=${text.take(80).replace("\n", "\\n")}")
    }

    fun vibrate(ms: Int) {
        // 触感开关在设置里可关；关掉后所有 JS 侧的振动请求直接丢弃。
        if (!com.zerolinux.app.core.GlobalSettings.load(context).haptics) return
        val v = context.getSystemService(Vibrator::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(5, 80), 80))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms.toLong().coerceIn(5, 80))
            }
        }
    }

    /**
     * 原生兜底弹键盘。
     * WebView 内部 `textarea.focus()` 只在页面内生效；WebView 自己没拿到窗口焦点时，
     * Android 不会弹 IME，所以 JS 明确请求时走这里切到主线程补一次。
     */
    private var imeRequestAt = 0L

    fun showIme() {
        // 去环：页面里的 focusin 钩子、selection 的 focusTerminal、宿主的焦点抖动都可能
        // 在很短时间里重复进来。350ms 内的重复请求直接丢弃，避免反复 requestFocus +
        // showSoftInput 把布局和 IME 动画打成连环。
        val now = android.os.SystemClock.uptimeMillis()
        if (now - imeRequestAt < 350L) return
        imeRequestAt = now
        webView.post {
            runCatching {
                // WebView 可能被 Compose 包在不可聚焦的容器里，先保证它自己能拿焦点。
                if (!webView.isFocusable) webView.isFocusable = true
                if (!webView.isFocusableInTouchMode) webView.isFocusableInTouchMode = true
                // 有意不用 requestFocusFromTouch()：它会顺带滚动到焦点并触发额外的
                // layout/scroll，键盘动画期间这一下很贵，可靠性也不比 requestFocus() 高。
                if (!webView.hasFocus()) webView.requestFocus()
                val imm = context.getSystemService(InputMethodManager::class.java) ?: return@runCatching
                // 不要用 imm.isActive(webView) 做短路：实测它在 WebView 上会返回 true
                // 而 mInputShown 仍为 false，导致键盘永远弹不出来。重复调用由上面的
                // 350ms 去抖兜住，不会形成抖动环。
                imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val FRAME_MS = 16L

        /** 暂停期间单会话最多攒这么多，超过就丢弃最旧一段。 */
        private const val MAX_BUFFERED = 512 * 1024

        /** 当前终端页的桥实例，供 Activity 在前后台切换时暂停/恢复推帧。 */
        @Volatile
        var active: PtyWebHost? = null
            private set
    }
}

/**
 * 注入到 WebView 的 `window.zlPty`。方法名必须与 web/src/bridge.ts 的
 * ZlPtyBridge 一一对应。
 */
class PtyWebBridge(
    private val host: PtyWebHost,
    private val context: Context,
    private val colsProvider: () -> Pair<Int, Int>
) {

    @JavascriptInterface
    fun open(id: String, containerUuid: String, cols: Int, rows: Int) {
        val existing = SessionRegistry.get(id)
        if (existing != null) return
        val (fallbackCols, fallbackRows) = colsProvider()
        // 用容器真实 id（用户可见名），不要截 uuid，否则抽屉里显示的是乱码前八位。
        val containerId = runCatching {
            com.zerolinux.app.container.ContainerRepo(context)
                .find(containerUuid)?.id
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: containerUuid.take(8)
        SessionRegistry.create(
            context,
            containerUuid,
            containerId,
            if (cols > 0) cols else fallbackCols,
            if (rows > 0) rows else fallbackRows
        )
    }

    /** 最近输出（base64）。WebView 被系统回收重建后，JS 用它在终端里补回历史。 */
    @JavascriptInterface
    fun history(id: String): String {
        val bytes = SessionRegistry.get(id)?.replay() ?: return ""
        if (bytes.isEmpty()) return ""
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun write(id: String, data: String) {
        SessionRegistry.get(id)?.write(data)
    }

    @JavascriptInterface
    fun resize(id: String, cols: Int, rows: Int) {
        SessionRegistry.get(id)?.resize(cols, rows)
    }

    @JavascriptInterface
    fun close(id: String) {
        SessionRegistry.remove(id)
    }

    @JavascriptInterface
    fun copy(text: String) {
        Log.i(TAG, "bridge copy ${text.length}")
        host.copy(text)
    }

    /** 读系统剪贴板；WebView 里 navigator.clipboard 不可用，粘贴按钮走这里。 */
    @JavascriptInterface
    fun readClipboard(): String {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context).toString()
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun vibrate(ms: Int) {
        host.vibrate(ms)
    }

    /** JS 侧点击终端区域后调用；WebView 内部 focus 弹不出 IME 时用它兜底。 */
    @JavascriptInterface
    fun showIme() {
        host.showIme()
    }

    @JavascriptInterface
    fun swipe(dir: String) {
        // 终端区域内的横滑：JS 判定为「明确的横向甩动」后调这里，
        // 用来开左/右抽屉。边缘那 24dp 是系统返回手势区，靠不住。
        swipeHandler?.invoke(dir)
    }

    @JavascriptInterface
    fun theme(mode: String) {
        // 主题由 Kotlin 侧统一 setTheme，这里只记录
        Log.d(TAG, "theme=$mode")
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "ZlPtyBridge"

        /** 由 TerminalActivity 注入：dir = "right" 开左抽屉，"left" 开右抽屉。 */
        @Volatile
        var swipeHandler: ((String) -> Unit)? = null
    }
}
