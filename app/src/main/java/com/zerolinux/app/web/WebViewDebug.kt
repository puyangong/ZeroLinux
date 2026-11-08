package com.zerolinux.app.web

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.WebView

/**
 * 仅 debug 包打开 WebView 内容调试（adb forward + CDP / chrome://inspect）。
 *
 * 必须在「真正承载 WebView 的那个进程里、创建任何 WebView 之前」调用。
 * 但注意顺序：如果本进程还要调 WebView.setDataDirectorySuffix(...)，必须先调它再调这里 ——
 * setWebContentsDebuggingEnabled 本身就会初始化 WebView 并锁定 data 目录，反了会让后缀失效。
 * ZeroLinux 是多进程应用（:main / :terminal），若在 :main 的 Application.onCreate 里调用，
 * 会先加载 Chromium 并抢走 WebView data directory 锁，随后 :terminal 创建 WebView 时
 * 直接报 "Using WebView from more than one process at once with the same data directory
 * is not supported"（crbug.com/558377）并崩溃。
 *
 * WebView 组件 < 113 时 android:debuggable 不会自动打开这个开关，必须显式调用；
 * >= 113 时显式调用同样安全。只对 debuggable=true 的包生效，release 不受影响。
 */
object WebViewDebug {

    private const val TAG = "WebViewDebug"

    fun enable(ctx: Context) {
        val appInfo = ctx.applicationInfo ?: return
        val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
            .onFailure { Log.w(TAG, "setWebContentsDebuggingEnabled failed: ${it.message}") }
    }
}
