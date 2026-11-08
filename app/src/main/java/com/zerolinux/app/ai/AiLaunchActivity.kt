package com.zerolinux.app.ai

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.zerolinux.app.R
import com.zerolinux.app.opencode.OpenCodeBridge

/**
 * `:ai` 进程里的透明跳板。
 *
 * 终端进程只负责启动这个 Activity；OpenCodeBridge 的端口分配、proot 安装、
 * 隐藏 WebView 写 localStorage、AAR 页面启动全部发生在 `:ai` 进程。
 * AAR 的 MainActivity 打开后本页立即 finish，不会留在返回栈里。
 */
class AiLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AiSupport.available) {
            runCatching {
                Toast.makeText(this, getString(R.string.ai_requires_android9), Toast.LENGTH_LONG)
                    .show()
            }
            finish()
            return
        }
        val action = intent?.action
        val uuid = intent?.getStringExtra(AiIntents.EXTRA_UUID)
        runCatching {
            when (action) {
                AiIntents.ACTION_OPEN_LAST -> {
                    OpenCodeBridge.openLast(this) { finish() }
                }
                AiIntents.ACTION_LAUNCH_FOR -> {
                    OpenCodeBridge.launchFor(this, uuid) { finish() }
                }
                else -> {
                    OpenCodeBridge.launchFromActive(this) { finish() }
                }
            }
        }.onFailure {
            Log.w("AiLaunchActivity", "start AI failed action=$action", it)
            finish()
        }
    }
}
