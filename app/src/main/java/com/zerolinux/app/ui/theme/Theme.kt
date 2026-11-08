package com.zerolinux.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.zerolinux.app.core.GlobalSettings

private val ZlColorScheme = darkColorScheme(
    primary = ZlAccent,
    onPrimary = Color(0xFF07120A),
    primaryContainer = ZlAccentDim,
    onPrimaryContainer = ZlFg,
    secondary = ZlPinned,
    onSecondary = Color(0xFF04121F),
    background = ZlBg,
    onBackground = ZlFg,
    surface = ZlSurface,
    onSurface = ZlFg,
    surfaceVariant = ZlSurfaceAlt,
    onSurfaceVariant = ZlMuted,
    outline = ZlBorder,
    outlineVariant = ZlBorder,
    error = ZlDanger,
    onError = Color(0xFF1A0503)
)

/**
 * 全局主题。
 *
 * [uiScale] 只缩放 Compose 原生 UI 的**字号**（Typography），不动 dp 尺寸，
 * 因此终端页/容器页/设置页/抽屉的文字会跟着变；WebView 终端自带 CSS，
 * 不受字号缩放影响。
 *
 * 传 null 时从 GlobalSettings 读（设置页会在 tick 变化后显式传入新值以实现
 * 「改完立即生效」）。
 */
@Composable
fun ZeroLinuxTheme(
    uiScale: Float? = null,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    // 保证进程内缓存已读入，uiScaleState 才有初值（同值赋值不会重组）。
    runCatching { GlobalSettings.load(ctx) }
    val live = GlobalSettings.uiScaleState.value
    val scale = (uiScale ?: live).coerceIn(0.85f, 1.4f)
    // 只放大 sp（字号），dp 触控尺寸与布局不随之放大。
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, d.fontScale * scale)
    ) {
        MaterialTheme(
            colorScheme = ZlColorScheme,
            typography = ZlTypography,
            content = content
        )
    }
}


/** 只缩放字号/行高/字距，其余（字重、字族）原样保留。 */
