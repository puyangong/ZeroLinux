package com.zerolinux.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerolinux.app.R
import com.zerolinux.app.ui.terminal.TerminalActivity

/**
 * :terminal 进程的前台服务宿主。第 1 步只负责「活着」并亮出通知；
 * 第 3 步起把 TerminalSession 的 fd 中转与通知聚合挂在这里。
 */
class TerminalHostService : android.app.Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        // 用 START_STICKY：宿主页退到后台后进程若被系统回收，服务会被重建，
        // 终端页恢复时不至于整页空白（会话进程仍由系统保留时可直接重连）。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_zl)
        .setContentTitle(getString(R.string.notif_title))
        .setContentText(getString(R.string.notif_text))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setShowWhen(false)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, TerminalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "zl_container_sessions"
        const val NOTIFICATION_ID = 1001

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, TerminalHostService::class.java)
                )
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, TerminalHostService::class.java)) }
        }
    }
}
