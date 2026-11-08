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
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.ui.container.ContainerListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 容器任务的前台服务。下载 rootfs、解压、apt 装包动辄十几分钟，
 * 没有前台服务的话进程随时会被系统回收。
 */
class ContainerTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForegroundCompat(getString(R.string.task_notification_title))
        watchJob = scope.launch {
            // 多任务：只要列表里还有 running（含排队中）的条目就不能收前台通知，
            // 否则「克隆 A + 创建 B」时 A 一结束前台服务就被拆掉，B 会在后台被杀。
            ContainerTasks.tasks.collectLatest { list ->
                if (list.isEmpty()) {
                    // 删除/清理后列表可能直接变空：给 1.2s 收尾窗口，确认没有新任务
                    // 或正在删除的容器再停服务，避免前台通知常驻。
                    delayStop()
                    return@collectLatest
                }
                val running = list.filter { it.running }
                if (running.isEmpty()) {
                    val last = list.last()
                    val text = last.error
                        ?: getString(R.string.task_notification_done, last.containerId)
                    notify(build(text, 0, 0, -1))
                    ServiceCompat.stopForeground(this@ContainerTaskService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    delayStop()
                    return@collectLatest
                }
                // 通知栏只能放一条：展示最近入队的任务，其余折叠成 `+N`。
                val head = running.last()
                val p = head.progress
                val base = when {
                    head.queued -> getString(R.string.containers_task_queued)
                    !p?.detail.isNullOrBlank() -> p.detail
                    p != null -> "${head.containerId} · ${p.stage.name.lowercase()}" + if (p.total > 0) " ${p.step}/${p.total} ${p.percent}%" else ""
                    else -> head.containerId
                }
                val extra = running.size - 1
                val text = if (extra > 0) "$base · +$extra" else base
                notify(build(text, p?.step ?: 0, p?.total ?: 0, p?.subPercent ?: -1))
            }
        }
    }

    private suspend fun delayStop() {
        kotlinx.coroutines.delay(1200)
        if (ContainerTasks.isBusy()) return
        NotificationManagerCompatStop()
        stopSelf()
    }

    private fun NotificationManagerCompatStop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(text, 0, 0, -1), type)
    }

    private fun build(text: String, step: Int, total: Int, subPercent: Int): Notification {
        val intent = Intent(this, ContainerListActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zl)
            .setContentTitle(getString(R.string.task_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
        if (subPercent >= 0) {
            builder.setProgress(100, subPercent.coerceIn(0, 100), false)
        } else if (total > 0) {
            builder.setProgress(total, step.coerceIn(0, total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notify(n: Notification) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等再确认一次前台状态：onCreate 若因任何原因没走完，这里还能兜住
        // startForegroundService 的 5 秒契约。
        runCatching {
            startForegroundCompat(getString(R.string.task_notification_title))
        }
        if (ContainerTasks.isBusy()) return START_STICKY
        // 没有正在跑的任务就尽快收尾，不要留一个空的前台通知。
        scope.launch {
            kotlinx.coroutines.delay(800)
            if (!ContainerTasks.isBusy()) {
                ServiceCompat.stopForeground(this@ContainerTaskService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "zl_tasks"
        const val NOTIFICATION_ID = 1002

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, ContainerTaskService::class.java)
            )
        }

        fun stop(ctx: Context) {
            // 只有确实没在跑任务时才允许外部停服务，避免把刚 startForegroundService
            // 拉起、还没来得及 startForeground 的服务拆掉（会杀进程）。
            if (ContainerTasks.isBusy()) return
            runCatching { ctx.stopService(Intent(ctx, ContainerTaskService::class.java)) }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.task_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.task_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
