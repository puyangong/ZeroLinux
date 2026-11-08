package com.zerolinux.app.opencode

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 终端进程只需要的 AI 状态探测。
 *
 * 刻意不引用 OpenCodeBridge / AAR：`:terminal` 进程不应该为了把 AI 按钮
 * 点亮而加载整个 OpenCode 客户端和 WebView 相关类。
 */
object AiStatus {

    private const val MIN_PORT = 20000
    private const val MAX_PORT = 60000

    fun ping(port: Int): Boolean {
        if (port !in MIN_PORT..MAX_PORT) return false
        return runCatching {
            Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", port), 800)
            }
            true
        }.getOrDefault(false)
    }
}
