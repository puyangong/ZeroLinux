package com.zerolinux.app.ui.container

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerConfig
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.container.DistroCatalog
import com.zerolinux.app.container.DistroId
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMono
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZlRunning
import com.zerolinux.app.ui.theme.ZlSurface
import com.zerolinux.app.ui.theme.ZlSurfaceAlt
import com.zerolinux.app.ui.theme.ZlUnread
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun distroAccent(id: DistroId): Color =
    DistroCatalog.byKey(id.key)?.accent ?: ZlAccent

fun distroName(ctx: Context, id: DistroId): String {
    val res = DistroCatalog.byKey(id.key)?.nameRes ?: return id.key
    return ctx.getString(res)
}

enum class ContainerStatus { DELETING, NOT_READY, PENDING, READY, DEGRADED }

fun statusOf(cfg: ContainerConfig): ContainerStatus = when {
    !cfg.rootfsReady -> ContainerStatus.NOT_READY
    !cfg.initialized -> ContainerStatus.PENDING
    // 必需步骤都成功 = 可用；lastError 只剩 `optional failed: ...` 时降级为
    // 「已就绪（部分可选组件缺失）」，不再显示成红色失败。
    cfg.lastError.startsWith("optional") -> ContainerStatus.DEGRADED
    else -> ContainerStatus.READY
}

fun statusColor(status: ContainerStatus): Color = when (status) {
    ContainerStatus.DELETING -> ZlMuted
    ContainerStatus.NOT_READY -> ZlDanger
    ContainerStatus.PENDING -> ZlUnread
    ContainerStatus.DEGRADED -> ZlUnread
    ContainerStatus.READY -> ZlRunning
}

fun statusText(ctx: Context, status: ContainerStatus): String = when (status) {
    ContainerStatus.DELETING -> ctx.getString(R.string.container_status_deleting)
    ContainerStatus.NOT_READY -> ctx.getString(R.string.container_status_not_ready)
    ContainerStatus.PENDING -> ctx.getString(R.string.container_status_pending)
    ContainerStatus.DEGRADED -> ctx.getString(R.string.container_status_degraded)
    ContainerStatus.READY -> ctx.getString(R.string.container_status_ready)
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** 取不到就返回 null，UI 自己决定显示占位符。 */
fun lanIpv4(): String? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 多条任务横幅：最多同时显示 3 条，剩余折叠成「还有 N 个任务」。
 * 队列本身仍是串行的（ContainerTasks 的 Mutex），这里只负责把
 * 排队中 + 运行中 + 刚结束的任务全部可视化，避免后入队的任务
 * 把前一条的横幅顶掉。
 */
@Composable
fun TaskBanners(states: List<ContainerTasks.State>, modifier: Modifier = Modifier) {
    if (states.isEmpty()) return
    // 排队中/运行中的排在前面：刚结束的 3 秒残留不应该把真正在跑的任务挤到折叠里。
    val ordered = states.sortedWith(
        compareByDescending<ContainerTasks.State> { it.running }.thenBy { it.id }
    )
    val shown = ordered.take(3)
    val rest = ordered.size - shown.size
    Column(modifier = modifier.fillMaxWidth()) {
        shown.forEach { TaskBanner(it) }
        if (rest > 0) {
            Text(
                text = stringResource(R.string.containers_tasks_more, rest),
                style = ZlMonoSmall,
                color = ZlMuted,
                modifier = Modifier.padding(start = 22.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
fun TaskBanner(state: ContainerTasks.State?, modifier: Modifier = Modifier) {
    if (state == null) return
    val p = state.progress
    val text = when {
        state.error != null -> state.error
        state.queued -> stringResource(R.string.containers_task_queued)
        !state.running -> state.message
        !p?.detail.isNullOrBlank() -> p.detail
        p != null -> p.stage.name.lowercase()
        else -> state.containerId
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = ZlSurfaceAlt
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.kind.name.lowercase()} · ${state.containerId}",
                    style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (state.error != null) ZlDanger else ZlFg,
                    modifier = Modifier.weight(1f),
                    softWrap = true
                )
                if (state.running && (p?.total ?: 0) > 0) {
                    Text(
                        text = if ((p?.subPercent ?: -1) >= 0) "${p?.subPercent}%"
                        else "${p?.step ?: 0}/${p?.total ?: 0}",
                        style = ZlMonoSmall,
                        color = ZlMuted
                    )
                }
            }
            if (!text.isNullOrBlank()) {
                Text(
                    text = text,
                    style = ZlMonoSmall,
                    color = if (state.error != null) ZlDanger else ZlMuted,
                    modifier = Modifier.padding(top = 6.dp),
                    softWrap = true
                )
            }
            if (state.running) {
                val total = p?.total ?: 0
                val progress = if ((p?.subPercent ?: -1) >= 0) {
                    (p?.subPercent ?: 0).toFloat() / 100f
                } else if (total > 0) {
                    (p?.step ?: 0).toFloat() / total
                } else {
                    0f
                }
                if ((p?.subPercent ?: -1) >= 0 || total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 3.dp),
                        color = ZlAccent,
                        trackColor = ZlBorder
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 3.dp),
                        color = ZlAccent,
                        trackColor = ZlBorder
                    )
                }
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            style = ZlMonoSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            softWrap = true
        )
    }
}

@Composable
fun MonoLabel(text: String, color: Color = ZlMuted, modifier: Modifier = Modifier) {
    Text(text = text, style = ZlMonoSmall, color = color, modifier = modifier)
}

@Composable
fun FieldLabel(text: String) {
    Text(
        text = text,
        style = ZlMonoSmall,
        color = ZlMuted,
        modifier = Modifier.padding(bottom = 6.dp),
        fontFamily = ZlMono
    )
}

@Composable
fun DistroBadge(id: DistroId) {
    val accent = distroAccent(id)
    val ctx = LocalContext.current
    Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = 0.14f)) {
        Text(
            text = distroName(ctx, id),
            style = ZlMonoSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun SettingsDivider() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        color = ZlBorder
    ) { Column(Modifier.heightIn(min = 1.dp)) {} }
}
