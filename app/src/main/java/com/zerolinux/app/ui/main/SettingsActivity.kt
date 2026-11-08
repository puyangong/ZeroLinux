package com.zerolinux.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerolinux.app.BuildConfig
import com.zerolinux.app.R
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.core.PermissionsHelper
import com.zerolinux.app.core.Proc
import com.zerolinux.app.engine.NativeProbe
import com.zerolinux.app.engine.NativeReport
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.i18n.LocaleManager
import com.zerolinux.app.ui.common.PermissionRequester
import com.zerolinux.app.ui.common.PermissionRow
import com.zerolinux.app.ui.common.ZlCard
import com.zerolinux.app.ui.common.ZlDivider
import com.zerolinux.app.ui.common.ZlGhostButton
import com.zerolinux.app.ui.common.ZlKeyValue
import com.zerolinux.app.ui.common.ZlObserveResume
import com.zerolinux.app.ui.common.ZlSectionLabel
import com.zerolinux.app.ui.common.ZlTopBar
import com.zerolinux.app.ui.theme.ZeroLinuxTheme
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMono
import com.zerolinux.app.ui.theme.ZlMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity() {

    private var tick by mutableIntStateOf(0)
    private val permissions = PermissionRequester(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.observe { tick++ }

        setContent {
            // 主题要感知「界面字号」：settings 提到 Theme 外层读，tick 变化时
            // 整棵树（含 Typography）一起重组，改完立即生效。
            val settings = remember(tick) { GlobalSettings.load(this) }
            ZeroLinuxTheme {
                ZlObserveResume { tick++ }

                val report by produceState<NativeReport?>(initialValue = null, key1 = tick) {
                    value = withContext(Dispatchers.IO) { NativeProbe.run(this@SettingsActivity) }
                }

                val storageGranted = remember(tick) { PermissionsHelper.storageGranted(this) }
                val notificationGranted = remember(tick) { PermissionsHelper.notificationGranted(this) }
                val backgroundGranted = remember(tick) { PermissionsHelper.batteryUnrestricted(this) }

                var fontScale by remember(settings) { mutableStateOf(settings.fontScale) }
                var uiScale by remember(settings) { mutableStateOf(settings.uiScale) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZlBg)
                        .safeDrawingPadding()
                ) {
                    ZlTopBar(
                        title = stringResource(R.string.settings_title),
                        subtitle = Proc.name(),
                        onBack = { finish() }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        ZlSectionLabel(text = stringResource(R.string.settings_language))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LanguageChip(
                                label = stringResource(R.string.settings_language_system),
                                selected = settings.language == LocaleManager.SYSTEM
                            ) { applyLanguage(LocaleManager.SYSTEM) }
                            LanguageChip(
                                label = stringResource(R.string.settings_language_zh),
                                selected = settings.language == LocaleManager.ZH
                            ) { applyLanguage(LocaleManager.ZH) }
                            LanguageChip(
                                label = stringResource(R.string.settings_language_en),
                                selected = settings.language == LocaleManager.EN
                            ) { applyLanguage(LocaleManager.EN) }
                        }

                        ZlSectionLabel(text = stringResource(R.string.settings_section_permissions))
                        ZlCard {
                            PermissionRow(
                                title = stringResource(R.string.perm_storage),
                                desc = stringResource(R.string.perm_storage_desc),
                                granted = storageGranted
                            ) { permissions.requestStorage() }
                            ZlDivider()
                            PermissionRow(
                                title = stringResource(R.string.perm_notification),
                                desc = stringResource(R.string.perm_notification_desc),
                                granted = notificationGranted
                            ) { permissions.requestNotification() }
                            ZlDivider()
                            PermissionRow(
                                title = stringResource(R.string.perm_background),
                                desc = stringResource(R.string.perm_background_desc),
                                granted = backgroundGranted
                            ) { permissions.requestBackground() }
                        }

                        ZlSectionLabel(text = stringResource(R.string.settings_section_runtime))
                        ZlCard {
                            ZlKeyValue(
                                label = stringResource(R.string.settings_process),
                                value = Proc.name()
                            )
                            ZlKeyValue(
                                label = stringResource(R.string.settings_abi),
                                value = report?.abi ?: "…"
                            )
                            ZlKeyValue(
                                label = stringResource(R.string.settings_proot),
                                value = report?.prootVersion ?: report?.error ?: "…",
                                valueColor = when {
                                    report == null -> ZlMuted
                                    report!!.prootOk -> ZlAccent
                                    else -> ZlDanger
                                }
                            )
                            ZlKeyValue(
                                label = stringResource(R.string.settings_pty),
                                value = when {
                                    report == null -> "…"
                                    report!!.ptyLoaded -> stringResource(R.string.settings_ok)
                                    else -> stringResource(R.string.settings_fail)
                                },
                                valueColor = if (report?.ptyLoaded == true) ZlAccent else ZlDanger
                            )
                            ZlKeyValue(
                                label = stringResource(R.string.terminal_libs),
                                value = "${report?.present?.size ?: 0} / 6"
                            )
                        }

                        ZlSectionLabel(text = stringResource(R.string.settings_section_about))
                        ZlCard {
                            ZlKeyValue(
                                label = stringResource(R.string.settings_version),
                                value = BuildConfig.VERSION_NAME
                            )
                            ZlDivider()
                            ZlKeyValue(label = "ZeroLinux", value = "GPL-2.0-or-later")
                            ZlKeyValue(label = "PRoot", value = "GPL-2.0-or-later")
                            ZlKeyValue(label = "talloc", value = "LGPL-3.0-or-later")
                            ZlKeyValue(label = "xterm.js", value = "MIT")
                            Text(
                                text = stringResource(R.string.settings_license_note),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = ZlMono),
                                color = ZlMuted,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        ZlSectionLabel(text = stringResource(R.string.settings_section_appearance))
                        ZlCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LanguageChip(
                                    label = stringResource(R.string.settings_theme_dark),
                                    selected = settings.theme == GlobalSettings.THEME_DARK
                                ) {
                                    GlobalSettings.update(this@SettingsActivity) {
                                        it.copy(theme = GlobalSettings.THEME_DARK)
                                    }
                                    tick++
                                }
                                LanguageChip(
                                    label = stringResource(R.string.settings_theme_light),
                                    selected = settings.theme == GlobalSettings.THEME_LIGHT
                                ) {
                                    GlobalSettings.update(this@SettingsActivity) {
                                        it.copy(theme = GlobalSettings.THEME_LIGHT)
                                    }
                                    tick++
                                }
                            }
                            ZlDivider()
                            // 终端字体只推给 xterm.js 的 setFontSize，不参与 Compose 字号。
                            Text(
                                text = stringResource(R.string.settings_font_scale),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = ZlMono,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = ZlFg
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_font_scale_desc,
                                    (fontScale * 100).toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = ZlMono),
                                color = ZlMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Slider(
                                value = fontScale,
                                onValueChange = { fontScale = it },
                                onValueChangeFinished = {
                                    GlobalSettings.update(this@SettingsActivity) {
                                        it.copy(fontScale = fontScale)
                                    }
                                },
                                valueRange = 0.85f..1.8f,
                                steps = 18,
                                modifier = Modifier.fillMaxWidth()
                            )
                            ZlDivider()
                            // 「全局字体」只缩放 Compose 原生 UI 的字号（WebView 终端
                            // 自带 CSS，天然不受影响）。
                            Text(
                                text = stringResource(R.string.settings_ui_scale),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = ZlMono,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = ZlFg
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_ui_scale_desc,
                                    (uiScale * 100).toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = ZlMono),
                                color = ZlMuted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Slider(
                                value = uiScale,
                                onValueChange = {
                                    uiScale = it
                                    // 实时生效：主题读的是这个 State，不必等落盘。
                                    GlobalSettings.uiScaleState.value = it
                                },
                                onValueChangeFinished = {
                                    GlobalSettings.update(this@SettingsActivity) {
                                        it.copy(uiScale = uiScale)
                                    }
                                    // 主题在 Theme 外层读 settings，必须 tick++ 才会重算 Typography
                                    tick++
                                },
                                valueRange = 0.85f..1.4f,
                                steps = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            ZlGhostButton(text = stringResource(R.string.settings_restart_wizard)) {
                                GlobalSettings.update(this@SettingsActivity) { it.copy(wizardDone = false) }
                                startActivity(Intent(this@SettingsActivity, WizardActivity::class.java))
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = ZlMono),
                color = ZlFg
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = ZlMono),
                color = ZlMuted
            )
        }
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = ZlMono,
                    fontWeight = FontWeight.Medium
                )
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ZlAccent.copy(alpha = 0.18f),
            selectedLabelColor = ZlAccent,
            labelColor = ZlMuted
        )
    )
}
