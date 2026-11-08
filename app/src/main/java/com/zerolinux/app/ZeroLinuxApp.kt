package com.zerolinux.app

import android.app.Application
import android.os.Build
import android.webkit.WebView
import com.zerolinux.app.ai.AiProcessInit
import com.zerolinux.app.container.Http
import com.zerolinux.app.core.AppPaths
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.core.Proc
import com.zerolinux.app.web.WebViewDebug

/**
 * 多进程架构：
 * - 主进程：容器管理、设置、容器任务；
 * - `:terminal`：终端会话、PTY、xterm WebView；
 * - `:ai`：OpenCode AAR、隐藏 WebView、opencode serve。
 *
 * Application 会在每个进程各执行一次，因此这里必须按进程名裁剪初始化：
 * 主进程不能初始化 AAR/提示音/WebView；终端进程不能加载 AI 相关类。
 *
 * 这里只做主线程上必须完成的最小初始化；证书解析和设置预读都放后台，
 * 避免冷启动首帧被磁盘 IO / X.509 解析拖慢。
 */
class ZeroLinuxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 目录创建是轻量 mkdirs，保持在主线程，保证后续路径可用。
        AppPaths.ensure(this)

        val process = Proc.name()
        val isAiProcess = process.endsWith(":ai")
        val isTerminalProcess = process.endsWith(":terminal")

        if (isAiProcess || isTerminalProcess) {
            // 每个承载 WebView 的进程必须使用不同的 data directory 后缀，
            // 否则 Chromium 会报 "Using WebView from more than one process at once"。
            // :ai 继续用 "term" 以保留旧版本 AAR 已写入的 localStorage/服务器列表；
            // :terminal 用新的 "terminal"，xterm 侧不依赖 localStorage。
            // 必须在任何 WebView 初始化之前调用。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val suffix = if (isAiProcess) "term" else "terminal"
                runCatching { WebView.setDataDirectorySuffix(suffix) }
                // 顺序不能反：setWebContentsDebuggingEnabled 本身就会初始化 WebView，
                // 先调它会让 setDataDirectorySuffix 失效。
                WebViewDebug.enable(this)
            } else if (isTerminalProcess) {
                // Android 8.x 不支持 WebView 多进程，:ai 侧已由 AiSupport 关闭；
                // 只让终端进程初始化 WebView，避免两个进程抢同一个 data directory。
                WebViewDebug.enable(this)
            }
        }

        if (isAiProcess) {
            // AI 专属初始化（AAR 顶栏、提示音、MainActivity 生命周期）全部
            // 放在 AiProcessInit 里，主进程/终端进程不会加载这些类。
            AiProcessInit.install(this)
        }

        Thread({
            // Http 的 Mozilla CA bundle 解析较重，放后台；下载发生前通常已完成，
            // 即便未完成，HttpsURLConnection 也会回落到系统 CA。
            runCatching { Http.init(this) }
            // 预热设置缓存：各 Activity 首次 load 不再读盘。
            runCatching { GlobalSettings.load(this) }
        }, "zl-app-init").apply { isDaemon = true }.start()
    }
}
