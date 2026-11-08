package com.zerolinux.app.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zerolinux.app.R

/**
 * 主进程 / 终端进程与 `:ai` 进程之间的唯一入口。
 *
 * 这里只放字符串和 Intent 组装，不引用 OpenCodeBridge、AAR 或任何 WebView
 * 类。这样 `:terminal` 点击 AI 时不会在自己的进程里把 AI 客户端加载起来，
 * 真正的启动逻辑全部发生在 `:ai` 的 AiLaunchActivity / AiCommandService 里。
 */
object AiIntents {

    const val ACTIVITY = "com.zerolinux.app.ai.AiLaunchActivity"
    const val SERVICE = "com.zerolinux.app.ai.AiCommandService"
    const val SERVER_SERVICE = "com.zerolinux.app.service.OpenCodeServerService"

    const val ACTION_OPEN_LAST = "com.zerolinux.app.ai.OPEN_LAST"
    const val ACTION_LAUNCH_ACTIVE = "com.zerolinux.app.ai.LAUNCH_ACTIVE"
    const val ACTION_LAUNCH_FOR = "com.zerolinux.app.ai.LAUNCH_FOR"

    const val ACTION_WATCH = "com.zerolinux.app.ai.WATCH"
    const val ACTION_FORGET = "com.zerolinux.app.ai.FORGET"

    // 与 OpenCodeServerService 的 ACTION_* 保持一致；常量集中在这里，
    // 主进程启动服务时就不必 import 服务类，避免把 AAR/OpenCodeBridge
    // 的类引用带进主进程。
    const val ACTION_SERVER_ENSURE = "com.zerolinux.app.opencode.ENSURE"
    const val ACTION_SERVER_START = "com.zerolinux.app.opencode.START"
    const val EXTRA_SERVER_UUID = "com.zerolinux.app.opencode.UUID"
    const val EXTRA_SERVER_RESTART = "com.zerolinux.app.opencode.RESTART"

    const val EXTRA_UUID = "com.zerolinux.app.ai.UUID"
    const val EXTRA_PORT = "com.zerolinux.app.ai.PORT"

    /** 冷启动恢复上次 AI 页面。 */
    fun openLast(ctx: Context) {
        if (!requireSupport(ctx)) return
        startActivity(ctx, ACTION_OPEN_LAST, null)
    }

    /** 顶栏 AI 按钮：恢复上次页面，否则打开当前容器。 */
    fun launchActive(ctx: Context) {
        if (!requireSupport(ctx)) return
        startActivity(ctx, ACTION_LAUNCH_ACTIVE, null)
    }

    fun launchFor(ctx: Context, uuid: String?) {
        if (!requireSupport(ctx)) return
        startActivity(ctx, ACTION_LAUNCH_FOR, uuid)
    }

    /** 让 `:ai` 进程拉起 AAR 的后台 watcher（不打开 UI）。 */
    fun watch(ctx: Context) {
        if (!AiSupport.available) return
        startService(ctx, ACTION_WATCH, null)
    }

    /** 容器删除后清理 `:ai` 里的服务器条目与 token。 */
    fun forget(ctx: Context, uuid: String, port: Int = 0) {
        if (!AiSupport.available) return
        startService(ctx, ACTION_FORGET, uuid, port)
    }

    /** 只保证后台 serve 在跑（冷启动/开机恢复），不打开 AI 页面。 */
    fun ensureServer(ctx: Context, uuid: String? = null) {
        if (!AiSupport.available) return
        runCatching {
            val intent = Intent()
                .setClassName(ctx.packageName, SERVER_SERVICE)
                .setAction(ACTION_SERVER_ENSURE)
            if (uuid != null) intent.putExtra(EXTRA_SERVER_UUID, uuid)
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    /** 选中/重启容器：让 `:ai` 重新准备 serve，不打开 AI 页面。 */
    fun startServerFor(ctx: Context, uuid: String, restart: Boolean = false) {
        if (!AiSupport.available) return
        runCatching {
            val intent = Intent()
                .setClassName(ctx.packageName, SERVER_SERVICE)
                .setAction(ACTION_SERVER_START)
                .putExtra(EXTRA_SERVER_UUID, uuid)
                .putExtra(EXTRA_SERVER_RESTART, restart)
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    /**
     * Android 8.x 没有 WebView 多进程支持，打开 AI 只会让 :ai 进程和
     * :terminal 进程同时初始化 WebView 而崩溃。这里明确提示并保持终端可用。
     */
    private fun requireSupport(ctx: Context): Boolean {
        if (AiSupport.available) return true
        runCatching {
            Toast.makeText(ctx, ctx.getString(R.string.ai_requires_android9), Toast.LENGTH_LONG)
                .show()
        }
        return false
    }

    private fun startActivity(ctx: Context, action: String, uuid: String?) {
        runCatching {
            val intent = Intent()
                .setClassName(ctx.packageName, ACTIVITY)
                .setAction(action)
            if (uuid != null) intent.putExtra(EXTRA_UUID, uuid)
            // AAR 的 OpenCodeMobile.start 内部直接 startActivity，不会补 NEW_TASK；
            // 从 Application/Service 上下文调用时由这里补，Activity 上下文保持同任务栈。
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    private fun startService(ctx: Context, action: String, uuid: String?, port: Int = 0) {
        runCatching {
            val intent = Intent()
                .setClassName(ctx.packageName, SERVICE)
                .setAction(action)
            if (uuid != null) intent.putExtra(EXTRA_UUID, uuid)
            if (port > 0) intent.putExtra(EXTRA_PORT, port)
            ctx.startService(intent)
        }
    }
}
