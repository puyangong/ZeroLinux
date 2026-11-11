package com.zerolinux.app.ui.container

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerConfig
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.opencode.AppStateStore
import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.ui.common.ZlCard
import com.zerolinux.app.ui.common.ZlGhostButton
import com.zerolinux.app.ui.common.ZlObserveResume
import com.zerolinux.app.ui.common.ZlPrimaryButton
import com.zerolinux.app.ui.common.ZlTopBar
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMono
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZeroLinuxTheme
import com.zerolinux.app.ui.theme.ZlMonoBody

/**
 * 容器管理：新建、切换、改 ID、克隆、删除、进单容器设置。
 */
class ContainerListActivity : BaseActivity() {

    private val repo by lazy { ContainerRepo(this) }

    /**
     * 容器 ID 的字符过滤：去掉 `/ \ : * ? " < > |`、空白与控制字符。
     * 与 [cloneIdBadChars] 同一口径，保证「输入时就干净」与「确认前再判一次」一致。
     */
    private fun sanitizeCloneId(raw: String): String = raw.filter { c ->
        c !in "/\\:*?\"<>|" && !c.isWhitespace() && !c.isISOControl()
    }

    private fun cloneIdBadChars(id: String): Boolean = id.any { c ->
        c in "/\\:*?\"<>|" || c.isWhitespace() || c.isISOControl()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZeroLinuxTheme {
                val ctx = LocalContext.current
                var snapshot by remember {
                    // 首帧先给空快照，磁盘读取放到 IO，避免冷启动卡主线程。
                    mutableStateOf(ContainerRepo.Snapshot(null, emptyList()))
                }
                var loaded by remember { mutableStateOf(false) }
                val revision by ContainerTasks.revision.collectAsState()
                // 多任务：同一时间可能有「克隆 A / 创建 B / 初始化 C」三条记录。
                val taskList by ContainerTasks.tasks.collectAsState()
                val deleting by ContainerTasks.deleting.collectAsState()

                LaunchedEffect(revision) {
                    val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repo.snapshot()
                    }
                    snapshot = next
                    loaded = true
                }
                LaunchedEffect(taskList) {
                    // 全部任务结束后把横幅收起，否则它会长在列表顶上。
                    if (taskList.isNotEmpty() && taskList.none { it.running }) {
                        kotlinx.coroutines.delay(1500)
                        ContainerTasks.clearFinished()
                    }
                }
                ZlObserveResume { snapshot = repo.snapshot() }

                var deleteTarget by remember { mutableStateOf<ContainerConfig?>(null) }
                var switchTarget by remember { mutableStateOf<ContainerConfig?>(null) }
                var cloneTarget by remember { mutableStateOf<ContainerConfig?>(null) }
                var cloneConfirm by remember { mutableStateOf<Pair<ContainerConfig, String>?>(null) }
                var cloneId by remember { mutableStateOf("") }

                // 「从 .zlbak 恢复」：导出的备份包必须能从 App 内选回来。
                // 之前只有 ContainerOps.restore 的实现，没有任何入口，用户只能把
                // .zlbak 丢进「从本地导入 rootfs」，结果 rootfs 下多出一层目录。
                val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        ContainerTasks.restore(ctx, uri)
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.containers_restore_started),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                fun switchTo(target: ContainerConfig) {
                    repo.setActive(target.uuid)
                    snapshot = repo.snapshot()
                    // 换了当前容器：旧容器的 AI 页面/服务器状态不能带过去。
                    AppStateStore.clearLastAiPage(this@ContainerListActivity)
                    AppStateStore.clearLastAiUuid(this@ContainerListActivity)
                    AiIntents.startServerFor(
                        this@ContainerListActivity,
                        target.uuid,
                        restart = false
                    )
                    android.widget.Toast.makeText(
                        this@ContainerListActivity,
                        getString(R.string.containers_switched, target.label),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZlBg)
                        .safeDrawingPadding()
                ) {
                    ZlTopBar(
                        title = stringResource(R.string.containers_title),
                        subtitle = if (snapshot.items.isEmpty()) {
                            stringResource(R.string.containers_empty_hint)
                        } else {
                            stringResource(
                                R.string.containers_count,
                                snapshot.items.size,
                                snapshot.items.firstOrNull { it.uuid == snapshot.active }?.label
                                    ?: stringResource(R.string.containers_none_active)
                            )
                        },
                        onBack = { finish() },
                        backAtEnd = true,
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    startActivity(Intent(ctx, NewContainerActivity::class.java))
                                }) {
                                    Text("+", fontSize = 28.sp, style = ZlMonoBody.copy(fontWeight = FontWeight.Bold), color = ZlAccent)
                                }
                                // 导入是低频但危险的操作，用带文字的按钮，避免和「+」的
                                // 图标按钮混在一起被误认成下载/刷新。
                                TextButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) {
                                    Text(
                                        text = stringResource(R.string.containers_import),
                                        style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                                        color = ZlAccent
                                    )
                                }
                            }
                        }
                    )

                    TaskBanners(taskList)

                    if (!loaded) {
                        // 首帧等 IO 读取，避免先闪一下「没有容器」。
                        Box(modifier = Modifier.fillMaxSize())
                    } else if (snapshot.items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.containers_empty_title),
                                    style = ZlMonoBody,
                                    color = ZlFg
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.containers_empty_desc),
                                    style = ZlMonoSmall,
                                    color = ZlMuted
                                )
                                Spacer(Modifier.height(20.dp))
                                ZlPrimaryButton(
                                    text = stringResource(R.string.containers_new),
                                    onClick = {
                                        startActivity(Intent(ctx, NewContainerActivity::class.java))
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                                ZlGhostButton(
                                    text = stringResource(R.string.containers_restore)
                                ) { restoreLauncher.launch(arrayOf("*/*")) }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(snapshot.items, key = { it.uuid }) { cfg ->
                                ContainerRow(
                                    cfg = cfg,
                                    active = cfg.uuid == snapshot.active,
                                    deleting = cfg.uuid in deleting,
                                    // 该容器已有 CREATE/INIT 在排队或运行 → 行内按钮禁用。
                                    busy = taskList.any {
                                        it.running &&
                                            (it.uuid == cfg.uuid ||
                                                (it.uuid == null && it.containerId == cfg.label))
                                    },
                                    onOpen = {
                                        startActivity(
                                            ContainerSettingsActivity.intent(ctx, cfg.uuid)
                                        )
                                    },
                                    onActivate = activate@{
                                        // 已经是当前容器：不弹、不切。
                                        if (cfg.uuid == snapshot.active) return@activate
                                        // 切换会清掉原容器的 AI 页面状态并改用它自己的
                                        // OpenCode 服务，属于有副作用的操作，先确认再执行。
                                        switchTarget = cfg
                                    },
                                    onClone = {
                                        // 不预填建议名：必须由用户显式输入，避免误点确认就建出副本。
                                        cloneId = ""
                                        cloneTarget = cfg
                                    },
                                    onDelete = { deleteTarget = cfg },
                                    onInit = {
                                        // 立即反馈：任务队列是异步的，前 1~2 秒可能还没有
                                        // progress，光靠横幅用户会以为没点中。
                                        android.widget.Toast.makeText(
                                            ctx,
                                            ctx.getString(
                                                R.string.container_init_start_toast,
                                                cfg.label
                                            ),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        ContainerTasks.initialize(ctx, cfg)
                                    }
                                )
                            }
                        }
                    }
                }

                cloneConfirm?.let { (target, newId) ->
                    AlertDialog(
                        onDismissRequest = { cloneConfirm = null },
                        title = { Text(stringResource(R.string.containers_clone_confirm_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.containers_clone_confirm_desc,
                                    target.label,
                                    newId
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                ContainerTasks.clone(ctx, target.uuid, newId, target.label)
                                cloneConfirm = null
                            }) { Text(stringResource(R.string.containers_clone_action)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { cloneConfirm = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                switchTarget?.let { target ->
                    AlertDialog(
                        onDismissRequest = { switchTarget = null },
                        title = { Text(stringResource(R.string.containers_switch_confirm_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.containers_switch_confirm_desc,
                                    target.label
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                switchTarget = null
                                switchTo(target)
                            }) { Text(stringResource(R.string.containers_action_use)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { switchTarget = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                deleteTarget?.let { target ->
                    AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text(stringResource(R.string.containers_delete_title)) },
                        text = {
                            Text(stringResource(R.string.containers_delete_desc, target.label))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                ContainerTasks.delete(ctx, target.uuid, target.label)
                                deleteTarget = null
                                snapshot = repo.snapshot()
                            }) { Text(stringResource(R.string.action_delete), color = ZlDanger) }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteTarget = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                cloneTarget?.let { target ->
                    // 现有 ID 集合用于重名判定；snapshot 变化（切换/删除后）这里跟着刷新。
                    val takenIds = remember(target, snapshot) {
                        repo.snapshot().items.map { it.id }.toSet()
                    }
                    val candidate = cloneId.trim()
                    val idError = when {
                        candidate.isEmpty() -> null
                        candidate.length > 40 -> R.string.containers_clone_err_invalid
                        cloneIdBadChars(candidate) -> R.string.containers_clone_err_invalid
                        candidate == target.id || candidate in takenIds ->
                            R.string.containers_clone_err_taken
                        else -> null
                    }
                    AlertDialog(
                        onDismissRequest = { cloneTarget = null },
                        title = { Text(stringResource(R.string.containers_clone_title)) },
                        text = {
                            Column {
                                Text(
                                    stringResource(R.string.containers_clone_desc, target.label),
                                    style = ZlMonoSmall,
                                    color = ZlMuted
                                )
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = cloneId,
                                    onValueChange = { cloneId = sanitizeCloneId(it) },
                                    label = { Text(stringResource(R.string.container_field_id)) },
                                    placeholder = { Text(stringResource(R.string.containers_clone_hint)) },
                                    singleLine = true,
                                    isError = idError != null,
                                    supportingText = if (idError != null) {
                                        { Text(stringResource(idError)) }
                                    } else null
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val t = target
                                val id = cloneId.trim()
                                // 二次校验：对话框打开期间列表可能已被别处改动。
                                val now = runCatching { repo.snapshot().items.map { it.id } }
                                    .getOrDefault(emptyList())
                                val okNow = id.isNotEmpty() && id.length <= 40 &&
                                    sanitizeCloneId(id) == id && id != t.id &&
                                    id !in now && !cloneIdBadChars(id)
                                if (!okNow) {
                                    cloneId = id
                                    return@TextButton
                                }
                                cloneTarget = null
                                cloneConfirm = t to id
                            }, enabled = cloneId.isNotBlank() && idError == null) {
                                Text(stringResource(R.string.containers_clone_action))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { cloneTarget = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    cfg: ContainerConfig,
    active: Boolean,
    deleting: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
    onInit: () -> Unit
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val status = if (deleting) ContainerStatus.DELETING else statusOf(cfg)
    val actionBusy = busy || deleting
    val accent = distroAccent(cfg.distro)
    // 容器行副标题追加体积信息（口径同终端页：拿不到容器内进程 pid 时用
    // rootfs 体积 = 手机存储占用）。首帧只读缓存，绝不扫盘；缓存缺失时才在
    // 后台补算一次，并按 uuid 错峰，避免所有卡片同时递归几十万文件。
    val usage = androidx.compose.runtime.produceState(
        initialValue = "",
        cfg.uuid
    ) {
        val rootfs = com.zerolinux.app.core.AppPaths.containerRootfs(ctx, cfg.uuid)
        fun render(bytes: Long): String = ctx.getString(
            R.string.container_storage,
            com.zerolinux.app.core.SysInfo.fmtBytes(bytes)
        )
        // 1) 内存/磁盘缓存命中就立刻显示，零递归。
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.zerolinux.app.core.SysInfo.peekDirBytes(rootfs)
        }
        if (cached != null) value = render(cached)
        // 2) 没有缓存才补算一次；扫描自身已降为 BACKGROUND 且同目录单飞。
        if (cached == null) {
            kotlinx.coroutines.delay((cfg.uuid.hashCode().and(Int.MAX_VALUE) % 8) * 900L)
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.zerolinux.app.core.SysInfo.dirBytes(rootfs)
            }
            value = render(bytes)
        }
        // 3) 之后每分钟只读缓存刷新文字，不再触发全盘扫描。
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.zerolinux.app.core.SysInfo.peekDirBytes(rootfs)
            } ?: continue
            value = render(next)
        }
    }.value

    Box {
        ZlCard(
            modifier = Modifier.combinedClickable(
                enabled = !deleting,
                onClick = onOpen,
                onLongClick = { menu = true }
            ),
            borderColor = if (active) ZlAccent.copy(alpha = 0.55f) else ZlBorder
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = cfg.label,
                            style = ZlMonoBody.copy(fontWeight = FontWeight.Bold),
                            color = ZlFg
                        )
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            StatusPill(stringResource(R.string.containers_active), ZlAccent)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(
                            distroName(ctx, cfg.distro),
                            usage.takeIf { it.isNotBlank() } ?: "",
                            initStepLabel(ctx, cfg.initStep) ?: ""
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = ZlMonoSmall,
                        color = ZlMuted
                    )
                    if (cfg.lastError.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        val optionalOnly = cfg.initialized && cfg.lastError.startsWith("optional")
                        Text(
                            text = if (optionalOnly) {
                                stringResource(
                                    R.string.container_optional_missing,
                                    cfg.lastError.removePrefix("optional failed:").trim()
                                )
                            } else {
                                stringResource(R.string.container_init_failed, cfg.lastError)
                            },
                            style = ZlMonoSmall,
                            color = if (optionalOnly) ZlMuted else ZlDanger
                        )
                    }
                    if (status != ContainerStatus.READY && status != ContainerStatus.DELETING) {
                        Spacer(Modifier.height(6.dp))
                        // 行上直接可见的入口：PENDING/NOT_READY 不该只藏在长按菜单里。
                        // busy = 该容器已有 CREATE/INIT 在排队/运行或正在删除 → 禁用，避免重复入队。
                        val ink = if (actionBusy) ZlMuted else ZlAccent
                        Text(
                            text = when {
                                actionBusy -> stringResource(R.string.container_init_running)
                                cfg.lastError.isNotBlank() ->
                                    stringResource(R.string.container_init_retry)
                                else -> stringResource(R.string.container_init_click)
                            },
                            style = ZlMonoSmall.copy(fontWeight = FontWeight.Bold),
                            color = ink,
                            modifier = Modifier
                                .background(ink.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                .clickable(enabled = !actionBusy) { onInit() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                StatusPill(statusText(ctx, status), statusColor(status))
                if (!deleting) {
                    IconButton(onClick = { menu = true }) {
                        Text("⋯", style = ZlMonoSmall, color = ZlMuted)
                    }
                }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.containers_action_use))
                        Text(
                            text = stringResource(R.string.containers_action_use_sub),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = ZlMuted
                        )
                    }
                },
                onClick = { menu = false; onActivate() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.containers_action_init)) },
                onClick = { menu = false; onInit() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.containers_action_clone)) },
                onClick = { menu = false; onClone() }
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.action_delete), color = ZlDanger)
                },
                onClick = { menu = false; onDelete() }
            )
        }
    }
}

/**
 * initStep 里 `bootstrap:3/5` 这种内部记账串不该直接甩给用户看，
 * 行副标题只需要「初始化中 · 3/5」。其它取值（空串 / `mirror`）返回 null。
 */
private fun initStepLabel(ctx: android.content.Context, initStep: String): String? {
    if (initStep == "mirror") return ctx.getString(R.string.container_init_progress)
    if (!initStep.startsWith("bootstrap:")) return null
    val detail = initStep.removePrefix("bootstrap:").trim()
    if (detail.isEmpty()) return ctx.getString(R.string.container_init_progress)
    val done = detail.substringBefore('/').toIntOrNull() ?: 0
    val total = detail.substringAfter('/', "5").toIntOrNull() ?: 5
    val pct = if (total > 0) done * 100 / total else 0
    return ctx.getString(R.string.container_init_progress_detail, "$detail · $pct%")
}
