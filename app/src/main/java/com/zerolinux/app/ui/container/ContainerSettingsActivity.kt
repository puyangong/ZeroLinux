package com.zerolinux.app.ui.container

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerConfig
import com.zerolinux.app.container.ContainerOps
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.ui.common.ZlCard
import com.zerolinux.app.ui.common.ZlGhostButton
import com.zerolinux.app.ui.common.ZlKeyValue
import com.zerolinux.app.ui.common.ZlObserveResume
import com.zerolinux.app.ui.common.ZlPrimaryButton
import com.zerolinux.app.ui.common.ZlSectionLabel
import com.zerolinux.app.ui.common.ZlTopBar
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZeroLinuxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 单容器设置：ID / 镜像源 / 初始化 / 克隆 / 备份 / 删除。 */
class ContainerSettingsActivity : BaseActivity() {

    companion object {
        private const val EXTRA_UUID = "container_uuid"

        /** true = 进来后自动补一次初始化（引导页建完实例直接跳过来时用）。 */
        const val EXTRA_AUTO_INIT = "auto_init"

        fun intent(ctx: Context, uuid: String): Intent =
            Intent(ctx, ContainerSettingsActivity::class.java).putExtra(EXTRA_UUID, uuid)
    }

    private val repo by lazy { ContainerRepo(this) }
    private val uuid: String by lazy { intent.getStringExtra(EXTRA_UUID).orEmpty() }
    private val autoInit: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_AUTO_INIT, false)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZeroLinuxTheme {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                var cfg by remember { mutableStateOf<ContainerConfig?>(null) }
                var loaded by remember { mutableStateOf(false) }
                val revision by ContainerTasks.revision.collectAsState()
                val tasks by ContainerTasks.tasks.collectAsState()
                var autoInitStarted by rememberSaveable { mutableStateOf(false) }
                var finishedInitTask by rememberSaveable { mutableLongStateOf(-1L) }
                var notice by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    cfg = withContext(Dispatchers.IO) { repo.find(uuid) }
                    loaded = true
                }
                LaunchedEffect(revision) {
                    cfg = withContext(Dispatchers.IO) { repo.find(uuid) }
                }
                ZlObserveResume {
                    scope.launch {
                        cfg = withContext(Dispatchers.IO) { repo.find(uuid) }
                    }
                }

                val initTask = remember(tasks, uuid) {
                    tasks.lastOrNull {
                        it.kind == ContainerTasks.Kind.INIT && it.uuid == uuid
                    }
                }

                // 引导页建好实例后自动补一次初始化；失败就停在管理页，
                // 用户可以点「重建」重试，不会被卡住。
                LaunchedEffect(cfg?.uuid, autoInit, autoInitStarted) {
                    val c = cfg ?: return@LaunchedEffect
                    if (!autoInit || autoInitStarted || c.initialized) return@LaunchedEffect
                    autoInitStarted = true
                    ContainerTasks.initialize(ctx, c)
                }

                // 初始化成功后：刷新文件抽屉根目录。
                LaunchedEffect(initTask?.id, initTask?.running, initTask?.error, uuid) {
                    val task = initTask ?: return@LaunchedEffect
                    if (task.running || task.id == finishedInitTask) return@LaunchedEffect
                    val latest = withContext(Dispatchers.IO) { repo.find(uuid) }
                        ?: return@LaunchedEffect
                    if (task.error != null || !latest.initialized) {
                        notice = ctx.getString(
                            R.string.container_auto_init_failed,
                            task.error.orEmpty()
                        )
                        return@LaunchedEffect
                    }
                    finishedInitTask = task.id
                    // 刷新目录碰文件系统，放 IO 线程。
                    withContext(Dispatchers.IO) {
                        ContainerLauncher.refreshFiles(ctx, uuid)
                    }
                    notice = ctx.getString(R.string.container_auto_init_done)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZlBg)
                        .safeDrawingPadding()
                ) {
                    ZlTopBar(
                        title = cfg?.label ?: uuid.take(8),
                        subtitle = cfg?.let { "${it.distro.key} · ${it.uuid.take(8)}" },
                        onBack = { finish() }
                    )
                    val c = cfg
                    if (!loaded) {
                        // 等 IO 读取，避免先闪「容器不存在」。
                    } else if (c == null) {
                        Text(
                            text = stringResource(R.string.container_missing),
                            color = ZlDanger,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Body(
                            ctx = ctx,
                            cfg = c,
                            repo = repo,
                            scope = scope,
                            notice = notice,
                            onChanged = {
                                scope.launch {
                                    cfg = withContext(Dispatchers.IO) { repo.find(uuid) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(
    ctx: Context,
    cfg: ContainerConfig,
    repo: ContainerRepo,
    scope: kotlinx.coroutines.CoroutineScope,
    notice: String?,
    onChanged: () -> Unit
) {
    val status = statusOf(cfg)

    var idText by remember { mutableStateOf(cfg.id) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmClone by remember { mutableStateOf(false) }
    var cloneSuggestedId by remember { mutableStateOf("") }
    var openCodePassword by remember {
        mutableStateOf(cfg.opencodePassword.ifBlank { "admin" })
    }

    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        // 用 octet-stream 而不是 application/gzip：否则系统会给文件名自动追加 .gz，
        // 变成 xxx.zlbak.gz，与界面上的命名约定不一致。
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) ContainerTasks.backup(ctx, cfg.uuid, uri, cfg.label)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        TaskBanner(ContainerTasks.state.collectAsState().value)
        notice?.let {
            Text(
                text = it,
                style = ZlMonoSmall,
                color = ZlAccent,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        ZlSectionLabel(stringResource(R.string.container_section_info))
        ZlCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DistroBadge(cfg.distro)
                Spacer(Modifier.width(8.dp))
                StatusPill(statusText(ctx, status), statusColor(status))
            }
            Spacer(Modifier.height(8.dp))
            ZlKeyValue(stringResource(R.string.container_field_uuid), cfg.uuid.take(18) + "…")
            ZlKeyValue(stringResource(R.string.container_field_created), fmtTime(cfg.createdAt))
            ZlKeyValue(stringResource(R.string.container_field_abi), cfg.abi)
            if (cfg.lastError.isNotBlank()) {
                val optionalOnly = cfg.initialized && cfg.lastError.startsWith("optional")
                ZlKeyValue(
                    stringResource(R.string.container_field_error),
                    if (optionalOnly) {
                        stringResource(
                            R.string.container_optional_missing,
                            cfg.lastError.removePrefix("optional failed:").trim()
                        )
                    } else {
                        cfg.lastError
                    },
                    if (optionalOnly) ZlMuted else ZlDanger
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        ZlSectionLabel(stringResource(R.string.container_section_identity))
        ZlCard {
            OutlinedTextField(
                value = idText,
                onValueChange = { idText = it.trim() },
                label = { Text(stringResource(R.string.container_field_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            ZlPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    message = null
                    if (idText != cfg.id) {
                        val err = repo.renameId(cfg.uuid, idText)
                        if (err != null) {
                            message = err
                            return@ZlPrimaryButton
                        }
                    }
                    message = ctx.getString(R.string.saved)
                    onChanged()
                }
            )
            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = ZlMonoSmall, color = ZlAccent)
            }
        }

        Spacer(Modifier.height(14.dp))
        ZlSectionLabel("OpenCode")
        ZlCard {
            OutlinedTextField(
                value = openCodePassword,
                onValueChange = { openCodePassword = it },
                label = { Text(stringResource(R.string.container_opencode_password)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.container_opencode_port,
                    if (cfg.opencodePort in 20000..60000) {
                        cfg.opencodePort.toString()
                    } else {
                        stringResource(R.string.container_opencode_port_auto)
                    }
                ),
                style = ZlMonoSmall,
                color = ZlMuted
            )
            Spacer(Modifier.height(10.dp))
            ZlPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    repo.update(cfg.uuid) {
                        it.copy(opencodePassword = openCodePassword.ifBlank { "admin" })
                    }
                    AiIntents.startServerFor(ctx, cfg.uuid, restart = true)
                    message = ctx.getString(R.string.saved)
                    onChanged()
                }
            )
            Spacer(Modifier.height(10.dp))
            // 手动把其它容器已配置的模型供应商追加进来；不在启动时自动做，
            // 避免用户还没确认就被写入别的容器的凭证。
            ZlGhostButton(
                text = stringResource(R.string.container_ai_share),
                onClick = {
                    scope.launch {
                        val added = withContext(Dispatchers.IO) {
                            com.zerolinux.app.opencode.AiProviderShare.sync(ctx, cfg.uuid)
                        }
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.container_ai_share_done, added),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.container_ai_share_hint),
                style = ZlMonoSmall,
                color = ZlMuted
            )
        }

        Spacer(Modifier.height(14.dp))
        ZlSectionLabel(stringResource(R.string.container_section_actions))
        ZlCard {
            ZlGhostButton(
                text = if (cfg.initialized) {
                    stringResource(R.string.container_action_rebuild)
                } else {
                    stringResource(R.string.container_action_init)
                },
                onClick = { confirmRebuild = true }
            )
            Spacer(Modifier.height(8.dp))
            ZlGhostButton(
                text = stringResource(R.string.containers_action_clone),
                onClick = {
                    cloneSuggestedId = repo.suggestId(cfg.distro)
                    confirmClone = true
                }
            )
            Spacer(Modifier.height(8.dp))
            ZlGhostButton(
                text = stringResource(R.string.container_action_backup),
                onClick = {
                    backupLauncher.launch("${cfg.id}-${ContainerOps.stamp()}.zlbak")
                }
            )
            Spacer(Modifier.height(8.dp))
            ZlGhostButton(
                text = stringResource(R.string.container_action_restart),
                onClick = { confirmRestart = true }
            )
            Spacer(Modifier.height(8.dp))
            ZlGhostButton(
                text = stringResource(R.string.action_delete),
                onClick = { confirmDelete = true }
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.container_footer),
            style = ZlMonoSmall,
            color = ZlMuted
        )
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.containers_delete_title)) },
            text = { Text(stringResource(R.string.containers_delete_desc, cfg.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            ContainerTasks.delete(ctx, cfg.uuid, cfg.label).join()
                            (ctx as? android.app.Activity)?.finish()
                        }
                    }
                }) { Text(stringResource(R.string.action_delete), color = ZlDanger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.container_status_deleting)) },
            text = { Text(stringResource(R.string.containers_delete_desc, cfg.label)) },
            confirmButton = {}
        )
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(stringResource(R.string.container_rebuild_title)) },
            text = { Text(stringResource(R.string.container_rebuild_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val rootfs = com.zerolinux.app.core.AppPaths.containerRootfs(ctx, cfg.uuid)
                            File(rootfs, "opt/zerolinux/.state").delete()
                        }
                        ContainerTasks.initialize(ctx, cfg.uuid, cfg.label)
                    }
                }) { Text(stringResource(R.string.container_rebuild_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRebuild = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.container_restart_title)) },
            text = { Text(stringResource(R.string.container_restart_desc, cfg.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    ContainerTasks.restart(ctx, cfg.uuid, cfg.label)
                    android.widget.Toast.makeText(
                        ctx,
                        ctx.getString(R.string.container_restarting),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) { Text(stringResource(R.string.container_restart_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmClone) {
        AlertDialog(
            onDismissRequest = { confirmClone = false },
            title = { Text(stringResource(R.string.containers_clone_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.containers_clone_confirm_desc,
                        cfg.label,
                        cloneSuggestedId
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClone = false
                    ContainerTasks.clone(ctx, cfg.uuid, cloneSuggestedId, cfg.label)
                    message = ctx.getString(R.string.container_cloning)
                }) { Text(stringResource(R.string.containers_clone_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClone = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

