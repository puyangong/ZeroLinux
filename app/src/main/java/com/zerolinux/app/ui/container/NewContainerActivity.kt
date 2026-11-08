package com.zerolinux.app.ui.container

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.container.DistroCatalog
import com.zerolinux.app.container.DistroId
import com.zerolinux.app.container.DistroImages
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.ui.common.ZlCard
import com.zerolinux.app.ui.common.ZlGhostButton
import com.zerolinux.app.ui.common.ZlPrimaryButton
import com.zerolinux.app.ui.common.ZlSectionLabel
import com.zerolinux.app.ui.common.ZlTopBar
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZlSurface
import com.zerolinux.app.ui.theme.ZeroLinuxTheme

/** 新建容器：选发行版 → 定 ID → 定端口 → 交给任务队列下载安装。 */
class NewContainerActivity : BaseActivity() {

    private val repo by lazy { ContainerRepo(this) }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = DistroId.fromKey(GlobalSettings.load(this).selectedDistro)

        setContent {
            ZeroLinuxTheme {
                val ctx = LocalContext.current
                var distro by remember { mutableStateOf(initial) }
                var id by remember { mutableStateOf(repo.suggestId(initial)) }
                var idTouched by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }

                // 首启离线包：用户自己已经下载好 rootfs 时跳过网络。
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        ContainerTasks.createFromArchive(ctx, distro, uri, id)
                        finish()
                    }
                }

                val idError = when {
                    id.isBlank() -> stringResource(R.string.err_id_empty)
                    !Regex("^[A-Za-z0-9._-]{1,32}$").matches(id) -> stringResource(R.string.err_id_charset)
                    repo.findById(id) != null -> stringResource(R.string.err_id_taken)
                    else -> null
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZlBg)
                        .safeDrawingPadding()
                ) {
                    ZlTopBar(
                        title = stringResource(R.string.new_container_title),
                        subtitle = stringResource(R.string.new_container_subtitle),
                        onBack = { finish() }
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp)
                    ) {
                        ZlSectionLabel(stringResource(R.string.new_container_distro))
                        distroGrid(selected = distro) { picked ->
                            distro = picked
                            if (!idTouched) id = repo.suggestId(picked)
                        }

                        Spacer(Modifier.height(18.dp))
                        ZlSectionLabel(stringResource(R.string.new_container_identity))
                        OutlinedTextField(
                            value = id,
                            onValueChange = { id = it.trim(); idTouched = true },
                            label = { Text(stringResource(R.string.container_field_id)) },
                            singleLine = true,
                            isError = idError != null,
                            supportingText = { if (idError != null) Text(idError, color = ZlDanger) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(18.dp))
                        ZlCard {
                            Text(
                                text = stringResource(R.string.new_container_note_title),
                                style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                                color = ZlFg
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.new_container_note),
                                style = ZlMonoSmall,
                                color = ZlMuted
                            )
                        }
                        // 设置页的「默认镜像」只影响这里：已建好的容器镜像源写在 guest 内，
                        // 不会被设置页回溯修改。
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.mirror_new_only_hint),
                            style = ZlMonoSmall,
                            color = ZlMuted
                        )
                        error?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, style = ZlMonoSmall, color = ZlDanger)
                        }
                        Spacer(Modifier.height(20.dp))
                    }

                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        ZlPrimaryButton(
                            text = stringResource(R.string.new_container_create),
                            enabled = idError == null,
                            onClick = {
                                error = null
                                ContainerTasks.create(ctx, distro, id)
                                finish()
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        ZlGhostButton(
                            text = stringResource(R.string.new_container_import),
                            onClick = {
                                error = null
                                importLauncher.launch(
                                    arrayOf("application/gzip", "application/x-xz", "*/*")
                                )
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        ZlGhostButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { finish() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun distroGrid(selected: DistroId, onPick: (DistroId) -> Unit) {
        val ctx = LocalContext.current
        val rows = DistroCatalog.all.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        val picked = option.id == selected
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    width = if (picked) 1.5.dp else 1.dp,
                                    color = if (picked) option.accent else ZlBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onPick(option.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (picked) option.accent.copy(alpha = 0.08f) else ZlSurface
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(option.accent, CircleShape)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = ctx.getString(option.nameRes),
                                        style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (picked) option.accent else ZlFg
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = ctx.getString(option.descRes),
                                    style = ZlMonoSmall,
                                    color = ZlMuted,
                                    softWrap = true
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
