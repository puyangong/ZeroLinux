package com.zerolinux.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerolinux.app.R
import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.ai.AiSupport
import com.zerolinux.app.opencode.OpenCodeBridge
import com.zerolinux.app.ui.container.ContainerListActivity

/**
 * 持有 opencode serve 的前台服务，固定运行在 `:ai` 进程：
 * - START_STICKY：进程被系统回收后由系统重建，onStartCommand 会重新拉起 active 容器的 serve；
 * - stopWithTask=false：从最近任务划掉 App 也不停；
 * - 开机/升级由 OpenCodeBootReceiver 拉起。
 *
 * 终端进程不再直接调用 OpenCodeBridge；所有启动/重启都通过 ACTION_START
 * 进入本服务，保证 proot 安装、serve 进程和隐藏 WebView 都留在 :ai。
 */
class OpenCodeServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AiSupport.available) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 系统按 START_STICKY 重建（intent == null）时也要自动恢复最后一次 AI 容器；
        // 显式启动带 ACTION_ENSURE，可通过 EXTRA_UUID 指定要恢复的容器。
        when (intent?.action) {
            null, ACTION_ENSURE -> {
                OpenCodeBridge.ensure(this, intent?.getStringExtra(EXTRA_UUID))
                return START_STICKY
            }
            ACTION_START -> {
                val uuid = intent.getStringExtra(EXTRA_UUID)
                if (uuid.isNullOrBlank()) {
                    OpenCodeBridge.ensure(this)
                } else {
                    OpenCodeBridge.startForUuid(
                        this,
                        uuid,
                        intent.getBooleanExtra(EXTRA_RESTART, false)
                    )
                }
                return START_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(), type)
    }

    private fun build(): Notification {
        val intent = Intent(this, ContainerListActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zl)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("OpenCode 容器服务运行中")
            .setStyle(NotificationCompat.BigTextStyle().bigText("OpenCode 容器服务运行中"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenCode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "OpenCode 容器服务"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "zl_opencode"
        const val NOTIFICATION_ID = 1003
        const val ACTION_ENSURE = AiIntents.ACTION_SERVER_ENSURE
        const val ACTION_START = AiIntents.ACTION_SERVER_START
        const val EXTRA_UUID = AiIntents.EXTRA_SERVER_UUID
        const val EXTRA_RESTART = AiIntents.EXTRA_SERVER_RESTART

        fun start(ctx: Context, uuid: String? = null) {
            if (!AiSupport.available) return
            // Android 12+ 后台启动 FGS 可能被拒；失败时下次打开 App 会再拉起。
            runCatching {
                val intent = Intent(ctx, OpenCodeServerService::class.java)
                    .setAction(ACTION_ENSURE)
                if (uuid != null) intent.putExtra(EXTRA_UUID, uuid)
                ContextCompat.startForegroundService(ctx, intent)
            }
        }

        /** 选中/重启容器：只保证后台 serve 在跑，不打开 AI 页面。 */
        fun startFor(ctx: Context, uuid: String, restart: Boolean = false) {
            if (!AiSupport.available) return
            runCatching {
                val intent = Intent(ctx, OpenCodeServerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_UUID, uuid)
                    .putExtra(EXTRA_RESTART, restart)
                ContextCompat.startForegroundService(ctx, intent)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, OpenCodeServerService::class.java)) }
        }
    }
}
