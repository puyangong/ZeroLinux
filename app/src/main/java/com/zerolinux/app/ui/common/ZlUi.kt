package com.zerolinux.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerolinux.app.R
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMono
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZlRunning
import com.zerolinux.app.ui.theme.ZlSurface

@Composable
fun ZlTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /**
     * 顶栏自身的 Modifier。
     *
     * 默认不处理系统栏：各页面通常已经在根布局 `safeDrawingPadding()` 里让过位。
     * edge-to-edge 下若要由顶栏自己贴住状态栏下方（终端页），传
     * `Modifier.statusBarsPadding()`，同时根布局不要再给 top inset，避免让两次。
     */
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    /** 标题左侧的自定义按钮（例如「+」）；默认没有。 */
    leading: @Composable RowScope.() -> Unit = {},
    /** 全 App 统一：返回按钮固定在右上角；需要旧的左侧返回时显式传 false。 */
    backAtEnd: Boolean = true,
    /**
     * 配色覆盖。
     *
     * 默认是宿主终端色板；AI 模块（AAR 的 MainActivity）的顶栏也复用这个
     * 组件，并同样直接使用默认色板，保证两条栏从首帧起完全一致。保留这几个
     * 参数是为了让特殊页面能在不改布局的前提下换色。
     */
    background: Color = ZlBg,
    foreground: Color = ZlFg,
    muted: Color = ZlMuted,
    divider: Color = ZlBorder
) {
    Surface(modifier = modifier, color = background) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    // 右侧多留 4dp 呼吸位，避免最后一个 action（齿轮）贴边被
                    // 边缘热区压住点击中心。
                    .padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!backAtEnd && onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = null,
                            tint = foreground
                        )
                    }
                } else if (!backAtEnd) {
                    Spacer(Modifier.width(12.dp))
                }
                leading()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = ZlMono,
                            fontWeight = FontWeight.Bold
                        ),
                        color = foreground,
                        // 不限制行数：大字号下标题自然折行，宁可顶栏长高也不截断。
                        softWrap = true
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = ZlMonoSmall,
                            color = muted,
                            // 大字号下副标题也允许自然折行，不做省略号截断（信息必须完整可读）。
                            softWrap = true
                        )
                    }
                }
                actions()
                if (backAtEnd && onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = null,
                            tint = foreground
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = divider)
        }
    }
}

/**
 * 终端页与 AI 模块共用的顶栏入口。
 *
 * 两条栏只有这一份实现：背景、状态栏让位、标题/副标题、返回键和动作区都
 * 在这里固定下来。终端页把它直接放进自己的 Column；AI 模块把它装进一个
 * `ComposeView` 覆盖在 AAR 页面顶部。两处都不再各自拼 Box、padding 和配色。
 */
@Composable
fun ZlTerminalTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxWidth().background(ZlBg)) {
        ZlTopBar(
            // 顶栏自己贴到状态栏下方：终端页根布局与 AI 覆盖层都不再让 top inset。
            modifier = modifier.statusBarsPadding(),
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions
        )
    }
}

@Composable
fun ZlIconAction(iconRes: Int, contentDescription: String?, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = ZlFg,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun ZlCard(
    modifier: Modifier = Modifier,
    borderColor: Color = ZlBorder,
    background: Color = ZlSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun ZlSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
        color = ZlMuted,
        modifier = modifier.padding(top = 8.dp, bottom = 10.dp)
    )
}

@Composable
fun ZlDot(color: Color, size: Dp = 8.dp) {
    Spacer(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun ZlKeyValue(label: String, value: String, valueColor: Color = ZlFg) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = ZlMonoSmall, color = ZlMuted, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = ZlMonoSmall,
            color = valueColor,
            // 值不限制行数：长路径 / 长版本串在大字号下换行显示，不做截断。
            softWrap = true
        )
    }
}

@Composable
fun ZlPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ZlAccent,
            contentColor = Color(0xFF07120A),
            disabledContainerColor = ZlBorder,
            disabledContentColor = ZlMuted
        )
    ) {
        Text(text = text, style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun ZlGhostButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text = text, style = ZlMonoSmall, color = if (enabled) ZlMuted else ZlBorder)
    }
}

@Composable
fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZlDot(if (granted) ZlRunning else ZlMuted, 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ZlFg
            )
            Text(text = desc, style = ZlMonoSmall, color = ZlMuted)
        }
        Spacer(Modifier.width(8.dp))
        // 「重新赋予权限」：图标 24sp、触控区 48dp —— 之前用 ZlGhostButton 的小字，
        // 点击目标太小又难对齐，这里和文件管理器刷新键保持同一套尺寸。
        IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
            Text(
                text = if (granted) "\u21bb" else "\uff0b",
                fontSize = 24.sp,
                color = if (granted) ZlMuted else ZlAccent
            )
        }
    }
}

@Composable
fun ZlDivider() {
    HorizontalDivider(thickness = 1.dp, color = ZlBorder.copy(alpha = 0.7f))
}

@Composable
fun ZlSpacer(height: Dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun ZlRowCentered(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
