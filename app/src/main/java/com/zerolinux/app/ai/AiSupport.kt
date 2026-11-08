package com.zerolinux.app.ai

import android.os.Build

/**
 * 多进程 WebView 的硬性门槛。
 *
 * `WebView.setDataDirectorySuffix` 从 Android 9（API 28）才存在。Android 8.x
 * 上同一个 App 的多个进程不能同时创建 WebView，否则 Chromium 会直接崩溃。
 * 因此 Android 8.x 保留终端（:terminal 进程）但关闭 AI（:ai 进程），
 * 避免为了 AI 把整个终端一起拖垮。
 */
object AiSupport {

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
