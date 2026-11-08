package com.zerolinux.app.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.zerolinux.app.opencode.AiSound
import com.zerolinux.app.ui.terminal.OpenCodeHeader

/**
 * `:ai` 进程专属初始化。
 *
 * 单独放在一个类里，避免主进程/终端进程加载 [ZeroLinuxApp] 时把 AAR、
 * OpenCodeHeader、提示音这些只属于 AI 的类一起带进来。只有进程名匹配
 * `:ai` 时才会执行到这里。
 */
object AiProcessInit {

    fun install(app: Application) {
        // AAR 内部所有页面顶栏统一成宿主终端顶栏的尺寸/配色；
        // 必须在任何 AAR 页面加载前持久化，避免首帧用旧样式。
        OpenCodeHeader.applyTopBar(app)

        // AAR 提示音统一交给宿主：只在 :ai 进程注册，避免终端进程
        // 也加载 AAR 的 MediaPlayer 相关类。
        // 宿主没播成功时 handler 会返回 false，让 AAR 自己的播放器兜底。
        AiSound.install(app)

        // AAR 的 MainActivity 返回键被库重写为空实现；在 :ai 进程内挂宿主顶栏，
        // 提供「返回终端」，并把它自己的顶栏压到宿主顶栏下方。
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is com.opencode.mobile.MainActivity) {
                    OpenCodeHeader.attach(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {
                if (activity is com.opencode.mobile.MainActivity) {
                    // 离开 AI 前先记录 hash 路由与当前服务器，切回时直接恢复原页面。
                    OpenCodeHeader.captureNow(activity)
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is com.opencode.mobile.MainActivity) {
                    OpenCodeHeader.detach(activity)
                }
            }
        })
    }
}
