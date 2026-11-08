package com.zerolinux.app.i18n

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.zerolinux.app.R
import java.io.File
import java.lang.ref.WeakReference

/**
 * 语言切换时的「切换中」悬浮窗。
 *
 * 切换语言会重建整条任务栈，旧 Activity 一旦 finish，挂在它上面的 Dialog 也会消失，
 * 所以用两个标记把「显示」接力给下一个 Activity：
 *  - [pending]：同进程实例之间的静态标记（recreate 场景）；
 *  - filesDir 下的时间戳文件：:main 与 :terminal 是两个进程，静态字段跨不过去。
 */
object LanguageOverlay {

    /** 同进程接力标记：旧页面置 true，新页面消费后置回 false。 */
    @Volatile
    var pending: Boolean = false
        private set

    private const val MARKER_NAME = "zl_lang_overlay.pending"

    /** 新 Activity 迟迟不来消费时的兜底时长，防止 Dialog 泄漏。 */
    private const val TIMEOUT_MS = 5_000L

    /** 跨进程标记的有效期：覆盖进程冷启动，同时避免旧标记误触发。 */
    private const val MARKER_TTL_MS = 10_000L

    /** 悬浮窗最短展示时长，避免只闪一帧（也给真机截图留出窗口）。 */
    private const val MIN_VISIBLE_MS = 500L

    /** 根容器自带半透明黑，系统 dim 必须关掉，否则会叠成两层。 */
    private const val BG_COLOR = 0x66000000
    private const val FG_COLOR = 0xFFE6E8EB.toInt()
    private const val ACCENT_COLOR = 0xFF7EE787.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var owner: WeakReference<Activity>? = null
    private var timeout: Runnable? = null
    private var shownAt = 0L

    /**
     * 记录一次切换并立刻挂出悬浮窗。
     * 先落标记再 show，保证紧接着创建的 Activity 一定能读到。
     */
    fun markPending(activity: Activity) {
        pending = true
        runCatching {
            markerFile(activity).writeText(SystemClock.elapsedRealtime().toString())
        }
        show(activity)
    }

    /** 是否有等待消费的标记；只读，不清理文件。 */
    fun isPending(context: Context): Boolean {
        if (pending) return true
        val at = markerTime(context) ?: return false
        val age = SystemClock.elapsedRealtime() - at
        return age in 0..MARKER_TTL_MS
    }

    /** 当前悬浮窗是否可见。 */
    fun isShowing(): Boolean = dialog?.isShowing == true

    /** 距最短展示时长还差多少毫秒（已满足则为 0）。 */
    fun remainingMinVisibleMs(): Long {
        val elapsed = SystemClock.elapsedRealtime() - shownAt
        return (MIN_VISIBLE_MS - elapsed).coerceAtLeast(0L)
    }

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val current = dialog
        if (current != null && current.isShowing && owner?.get() === activity) return
        // 旧 Activity 可能已销毁但 Dialog 引用还在，先清掉再挂到新页面上。
        if (current != null) dismiss()

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)

        val metrics = activity.resources.displayMetrics
        fun dp(value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), metrics).toInt()

        val root = FrameLayout(activity).apply {
            setBackgroundColor(BG_COLOR)
            // 拦住穿透点击，切换期间不允许再操作旧界面。
            isClickable = true
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ACCENT_COLOR)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        column.addView(TextView(activity).apply {
            text = activity.getString(R.string.language_switching)
            setTextColor(FG_COLOR)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        })
        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        d.setContentView(root)
        d.window?.apply {
            setDimAmount(0f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        dialog = d
        owner = WeakReference(activity)
        shownAt = SystemClock.elapsedRealtime()
        d.show()

        timeout?.let(handler::removeCallbacks)
        // 只有当前这个 Dialog 的兜底才生效，避免旧页面的计时器误关新页面的窗。
        val mine = d
        val guard = Runnable {
            if (dialog === mine) {
                pending = false
                runCatching { markerFile(activity).delete() }
                dismiss()
            }
        }
        timeout = guard
        handler.postDelayed(guard, TIMEOUT_MS)
    }

    /** 消费标记：关掉悬浮窗并清掉跨进程文件。 */
    fun consume(context: Context) {
        pending = false
        runCatching { markerFile(context).delete() }
        dismiss()
    }

    fun dismiss() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val d = dialog ?: return
        dialog = null
        owner = null
        runCatching { if (d.isShowing) d.dismiss() }
    }

    private fun markerFile(context: Context): File =
        File(context.applicationContext.filesDir, MARKER_NAME)

    private fun markerTime(context: Context): Long? = runCatching {
        val file = markerFile(context)
        if (!file.exists()) return@runCatching null
        file.readText().trim().toLongOrNull()
    }.getOrNull()
}
