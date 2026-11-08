package com.zerolinux.app.ui.main

import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.opencode.AppStateStore

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.zerolinux.app.core.AppPaths
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.ui.terminal.TerminalActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动器，只做路由，不承载业务：
 * 未完成引导 -> Wizard；已完成 -> 主终端。
 *
 * 设置读取放到 IO 线程，避免冷启动首帧被磁盘 IO 卡住。
 */
class LaunchActivity : BaseActivity() {

    /** 只做路由，启动主终端后立刻 finish；「切换中」悬浮窗留给 TerminalActivity 消费。 */
    override val consumesLanguageOverlay: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 重建（如语言切换）时直接结束，避免重复启动目标页。
        if (savedInstanceState != null) {
            finish()
            return
        }
        AppPaths.ensure(this)
        lifecycleScope.launch {
            val wizardDone = withContext(Dispatchers.IO) {
                GlobalSettings.load(this@LaunchActivity).wizardDone
            }
            if (isFinishing || isDestroyed) return@launch
            val target = if (wizardDone) TerminalActivity::class.java else WizardActivity::class.java
            val intent = Intent(this@LaunchActivity, target)
            if (wizardDone) {
                val lastScreen = withContext(Dispatchers.IO) {
                    AppStateStore.lastScreen(this@LaunchActivity)
                }
                val lastAi = withContext(Dispatchers.IO) {
                    AppStateStore.lastAiUuid(this@LaunchActivity)
                }
                if (lastAi != null) {
                    // 隐式拉起最后一次进入 AI 的容器；服务被系统重建时也会自动恢复。
                    AiIntents.ensureServer(this@LaunchActivity, lastAi)
                }
                // 即使没有容器记录也要恢复 AI 页面：用户可能一直用的是手动添加的远程服务器。
                if (lastScreen == "ai") {
                    intent.putExtra("open_ai", true)
                    if (lastAi != null) intent.putExtra("open_ai_uuid", lastAi)
                }
            }
            startActivity(intent)
            finish()
        }
    }
}
