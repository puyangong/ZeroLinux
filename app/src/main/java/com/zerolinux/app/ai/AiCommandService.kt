package com.zerolinux.app.ai

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.zerolinux.app.opencode.OpenCodeBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `:ai` 进程里的短命令服务（非前台服务）。
 *
 * 只处理不需要界面的动作：启动 AAR 后台 watcher、删除容器后清理服务器条目。
 * 需要长时间存活的 opencode serve 仍由 OpenCodeServerService 持有。
 */
class AiCommandService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AiSupport.available) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            AiIntents.ACTION_WATCH -> {
                OpenCodeBridge.ensureBackgroundWatch(this)
                stopSelf(startId)
            }
            AiIntents.ACTION_FORGET -> {
                val uuid = intent.getStringExtra(AiIntents.EXTRA_UUID)
                if (uuid.isNullOrBlank()) {
                    stopSelf(startId)
                } else {
                    val finished = AtomicBoolean(false)
                    val done = {
                        if (finished.compareAndSet(false, true)) stopSelf(startId)
                    }
                    // 隐藏 WebView 加载失败时不能让服务永远挂着。
                    mainHandler.postDelayed({ done() }, 10_000L)
                    OpenCodeBridge.forgetServer(
                        this,
                        uuid,
                        intent.getIntExtra(AiIntents.EXTRA_PORT, 0)
                    ) { done() }
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
