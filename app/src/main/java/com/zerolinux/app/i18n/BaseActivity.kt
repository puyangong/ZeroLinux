package com.zerolinux.app.i18n

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.zerolinux.app.R
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.ui.main.LaunchActivity

/** 所有 Activity 的基类：统一注入语言环境，并提供切换后的重建入口。 */
open class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 跨进程（:terminal）新建 Activity 时，进程内 cache 可能还是旧语言；
        // 必须在 wrap 之前失效，否则包出来的是旧 Locale。
        GlobalSettings.invalidate()
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 新实例接手「语言切换中」悬浮窗：先挂出来，等首帧绘制完再收，
        // 避免 Dialog 提前消失、中间露出黑底。
        if (LanguageOverlay.isPending(this)) {
            LanguageOverlay.show(this)
            if (consumesLanguageOverlay) {
                window.decorView.post {
                    // 再等最短展示时长：既避免一闪而过，也保证首帧已经绘制。
                    val wait = LanguageOverlay.remainingMinVisibleMs()
                    window.decorView.postDelayed({ LanguageOverlay.consume(this) }, wait)
                }
            }
        }
    }

    /** 纯路由页（如 LaunchActivity）只负责显示，消费交给真正落地的页面。 */
    protected open val consumesLanguageOverlay: Boolean = true

    protected fun applyLanguage(language: String) {
        GlobalSettings.update(this) { it.copy(language = language) }
        // 写入后本进程 cache 里仍是旧语言，先失效再重建，新界面才会读盘。
        GlobalSettings.invalidate()

        // 切换期间先挂上「切换中」悬浮窗；新页面首帧绘制后再关闭。
        LanguageOverlay.markPending(this)

        if (isTaskRoot) {
            // 任务栈里只有自己（冷启动直达本页）：就地重建即可。
            recreate()
            // recreate() 不经过 startActivity 的动画参数，需单独覆盖转场。
            applyFadeTransition()
            return
        }
        // 栈里还有 ContainerList / Terminal(singleTask) / Ai(singleTop) 等页面，
        // 只 recreate() 当前页会让它们保持旧语言（attachBaseContext 不会重跑）。
        // 清空任务栈后从 Launch 重新走一遍，让每个 Activity 重新解释语言。
        // 带 custom animation 启动，避免整栈重建瞬间闪黑。
        val options = ActivityOptions
            .makeCustomAnimation(this, R.anim.zl_fade_in, R.anim.zl_fade_out)
            .toBundle()
        startActivity(
            Intent(this, LaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            options
        )
        finishAffinity()
    }

    /** recreate() 后的淡入淡出：API 34+ 用新接口，低版本走已弃用的 overridePendingTransition。 */
    private fun applyFadeTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.zl_fade_in,
                R.anim.zl_fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.zl_fade_in, R.anim.zl_fade_out)
        }
    }
}
