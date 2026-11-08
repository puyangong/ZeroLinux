package com.zerolinux.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.container.DistroCatalog
import com.zerolinux.app.container.DistroOption
import com.zerolinux.app.core.AppPaths
import com.zerolinux.app.core.GlobalSettings
import com.zerolinux.app.core.PermissionsHelper
import com.zerolinux.app.i18n.BaseActivity
import com.zerolinux.app.ui.common.PermissionRequester
import com.zerolinux.app.ui.common.PermissionRow
import com.zerolinux.app.ui.common.ZlCard
import com.zerolinux.app.ui.common.ZlDivider
import com.zerolinux.app.ui.common.ZlGhostButton
import com.zerolinux.app.ui.common.ZlPrimaryButton
import com.zerolinux.app.ui.common.ZlSectionLabel
import com.zerolinux.app.ui.container.ContainerLauncher
import com.zerolinux.app.ui.container.ContainerSettingsActivity
import com.zerolinux.app.ui.container.NewContainerActivity
import com.zerolinux.app.ui.container.TaskBanners
import com.zerolinux.app.ui.theme.ZeroLinuxTheme
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBg
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMono
import com.zerolinux.app.ui.theme.ZlMonoBody
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZlSurface
import com.zerolinux.app.ui.terminal.TerminalActivity

/**
 * 首启引导：欢迎 -> 权限 -> 容器 -> 完成。
 *
 * 进度持久化在 global.json（与 [GlobalSettings] 同一套存储）：
 * - wizardDone=true 表示已完成（含跳过），之后启动直达 TerminalActivity；
 * - wizardStep 记录未完成时的当前步骤，进程被杀后重进可从上次步骤继续。
 * 每一步都可跳过，跳过后仍可在设置里补授权。
 */
class WizardActivity : BaseActivity() {

    companion object {
        const val STEP_WELCOME = 0
        const val STEP_PERMISSIONS = 1
        const val STEP_CONTAINER = 2
        const val STEP_READY = 3

        const val TOTAL_STEPS = 4
        const val LAST_STEP = STEP_READY
    }

    private var tick by mutableIntStateOf(0)
    private val permissions = PermissionRequester(this)

    /** 防止「完成」被连点两次而重复启动 Terminal。 */
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPaths.ensure(this)

        permissions.observe { tick++ }

        val context = this
        val settings = GlobalSettings.load(this)
        val initialDistro = settings.selectedDistro
        // 已完成过的引导（例如设置里的「重新运行引导」）总是从第一步开始；
        // 只有「未完成」的引导才从上次中断的步骤继续。
        val initialStep = if (settings.wizardDone) {
            STEP_WELCOME
        } else {
            settings.wizardStep.coerceIn(STEP_WELCOME, LAST_STEP)
        }

        val repo = ContainerRepo(this)

        setContent {
            ZeroLinuxTheme {
                var step by rememberSaveable { mutableStateOf(initialStep) }
                var selected by rememberSaveable { mutableStateOf(initialDistro) }
                // 第 3 步正在创建/已提交的容器 ID；非空时禁用「下一步」并显示进度。
                var pendingCreateId by rememberSaveable { mutableStateOf("") }
                var createError by rememberSaveable { mutableStateOf("") }
                var navigatedTaskId by rememberSaveable { mutableLongStateOf(-1L) }
                val tasks by ContainerTasks.tasks.collectAsState()

                ObserveResume { tick++ }

                // 每次换步落盘，进程被杀后重进可续上。
                LaunchedEffect(step) {
                    val current = GlobalSettings.load(context)
                    if (!current.wizardDone) {
                        GlobalSettings.update(context) { it.copy(wizardStep = step) }
                    }
                }

                // 返回键与 UI 一致：有「上一步」就退回，第一步不退出 App。
                BackHandler(enabled = true) {
                    if (step > STEP_WELCOME) step -= 1
                }

                val storageGranted = remember(tick) { PermissionsHelper.storageGranted(context) }
                val notificationGranted = remember(tick) { PermissionsHelper.notificationGranted(context) }
                val backgroundGranted = remember(tick) { PermissionsHelper.batteryUnrestricted(context) }

                val suggestedId = remember(selected) {
                    DistroCatalog.byKey(selected)?.let { distro ->
                        runCatching { repo.suggestId(distro.id) }.getOrNull()
                    }.orEmpty()
                }

                // 从「手动创建容器」返回后重新读一次，已有容器就显示它的名字。
                val existingContainer = remember(tick) {
                    runCatching { repo.active()?.id }.getOrNull().orEmpty()
                }

                // 创建任务结束后带 uuid 进管理页：初始化在那里跑，失败也能手动重试。
                val createTask = remember(tasks, pendingCreateId) {
                    if (pendingCreateId.isBlank()) {
                        null
                    } else {
                        tasks.lastOrNull {
                            it.kind == ContainerTasks.Kind.CREATE &&
                                it.containerId == pendingCreateId
                        }
                    }
                }

                LaunchedEffect(createTask?.id, createTask?.running, pendingCreateId) {
                    val task = createTask ?: return@LaunchedEffect
                    if (task.running || task.id == navigatedTaskId) return@LaunchedEffect
                    navigatedTaskId = task.id
                    val created = runCatching { repo.findById(pendingCreateId) }.getOrNull()
                    if (created == null) {
                        // 连配置都没落库：留在本步，用户可以直接重试。
                        createError = task.error ?: getString(R.string.wizard_create_missing)
                        pendingCreateId = ""
                        return@LaunchedEffect
                    }
                    pendingCreateId = ""
                    // 先把步骤推进到完成页，管理页返回时落在这里，而不是容器选择页。
                    step = STEP_READY
                    if (task.error != null) {
                        Toast.makeText(
                            context,
                            getString(R.string.wizard_create_failed, task.error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    startActivity(
                        ContainerSettingsActivity.intent(context, created.uuid)
                            .putExtra(ContainerSettingsActivity.EXTRA_AUTO_INIT, true)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZlBg)
                        .safeDrawingPadding()
                ) {
                    WizardTopBar(step = step, onClose = { finishWizard(context, selected) })
                    Box(modifier = Modifier.weight(1f)) {
                        when (step) {
                            STEP_WELCOME -> WelcomeStep()
                            STEP_PERMISSIONS -> PermissionsStep(
                                storageGranted = storageGranted,
                                notificationGranted = notificationGranted,
                                backgroundGranted = backgroundGranted,
                                onStorage = { permissions.requestStorage() },
                                onNotification = { permissions.requestNotification() },
                                onBackground = { permissions.requestBackground() }
                            )
                            STEP_CONTAINER -> ContainerStep(
                                selected = selected,
                                onSelect = { selected = it },
                                existingContainer = existingContainer,
                                suggestedId = suggestedId,
                                creating = pendingCreateId.isNotBlank(),
                                error = createError,
                                onCreateContainer = {
                                    startActivity(
                                        Intent(context, NewContainerActivity::class.java)
                                    )
                                }
                            )
                            else -> ReadyStep(
                                selected = selected,
                                suggestedId = existingContainer.ifBlank { suggestedId }
                            )
                        }
                    }
                    if (pendingCreateId.isNotBlank()) {
                        TaskBanners(
                            states = tasks.filter {
                                it.kind == ContainerTasks.Kind.CREATE &&
                                    it.containerId == pendingCreateId
                            }
                        )
                    }
                    WizardBottomBar(
                        step = step,
                        canContinue = step != STEP_CONTAINER ||
                            (selected.isNotBlank() && pendingCreateId.isBlank()),
                        onBack = { if (step > STEP_WELCOME) step -= 1 },
                        onNext = {
                            when {
                                step < STEP_CONTAINER -> step += 1
                                step == STEP_CONTAINER -> {
                                    createError = ""
                                    val active = runCatching { repo.active() }.getOrNull()
                                    if (active != null) {
                                        // 已有实例：不再重复创建，直接进管理页；
                                        // 之前初始化没跑完的话，管理页会补一次。
                                        step = STEP_READY
                                        startActivity(
                                            ContainerSettingsActivity.intent(context, active.uuid)
                                                .putExtra(
                                                    ContainerSettingsActivity.EXTRA_AUTO_INIT,
                                                    !active.initialized
                                                )
                                        )
                                    } else {
                                        val distro = DistroCatalog.byKey(selected)?.id
                                        val id = suggestedId.ifBlank {
                                            distro?.let {
                                                runCatching { repo.suggestId(it) }.getOrNull()
                                            }.orEmpty()
                                        }
                                        if (distro == null || id.isBlank()) {
                                            createError = getString(R.string.wizard_create_missing)
                                        } else {
                                            pendingCreateId = id
                                            ContainerLauncher.create(context, distro, id)
                                        }
                                    }
                                }
                                else -> finishWizard(context, selected)
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * 完成引导：写入 wizardDone 并清栈进入终端。
     * 跳过与「进入 ZeroLinux」走同一条路径 —— 跳过也算完成。
     */
    private fun finishWizard(context: WizardActivity, distro: String) {
        if (finishing) return
        finishing = true
        GlobalSettings.update(context) { settings ->
            settings.copy(
                wizardDone = true,
                wizardStep = STEP_WELCOME,
                selectedDistro = distro.takeIf { it.isNotBlank() } ?: settings.selectedDistro
            )
        }
        context.startActivity(
            Intent(context, TerminalActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        context.finish()
    }
}

@Composable
private fun ObserveResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun WizardTopBar(step: Int, onClose: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ZeroLinux",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = ZlMono,
                    fontWeight = FontWeight.Bold
                ),
                color = ZlAccent,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.step_of, step + 1, WizardActivity.TOTAL_STEPS),
                style = ZlMonoSmall,
                color = ZlMuted
            )
            Spacer(Modifier.width(12.dp))
            ZlGhostButton(text = stringResource(R.string.wizard_skip), onClick = onClose)
        }
        HorizontalDivider(thickness = 1.dp, color = ZlBorder)
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Text(
            text = "ZeroLinux",
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = ZlMono,
                fontWeight = FontWeight.Bold
            ),
            color = ZlFg
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.app_tagline),
            style = ZlMonoSmall,
            color = ZlAccent
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZlFg
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.welcome_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = ZlMuted
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = ZlMuted
        )

        Spacer(Modifier.height(26.dp))

        ZlCard(background = Color(0xFF05060A)) {
            Text(text = "\$ proot -0 -r rootfs /bin/sh", style = ZlMonoBody, color = ZlMuted)
            Text(text = "Welcome to ZeroLinux", style = ZlMonoBody, color = ZlFg)
            Row {
                Text(text = "root@zerolinux:~# ", style = ZlMonoBody, color = ZlAccent)
                Text(text = "_", style = ZlMonoBody, color = ZlAccent)
            }
        }

        Spacer(Modifier.height(26.dp))
        ZlSectionLabel(text = "01 PRoot · 02 PTY · 03 APT / APK")
    }
}

@Composable
private fun PermissionsStep(
    storageGranted: Boolean,
    notificationGranted: Boolean,
    backgroundGranted: Boolean,
    onStorage: () -> Unit,
    onNotification: () -> Unit,
    onBackground: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 26.dp)
    ) {
        Text(
            text = stringResource(R.string.perm_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZlFg
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.perm_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = ZlMuted
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.perm_storage_why),
            style = MaterialTheme.typography.bodyMedium,
            color = ZlMuted
        )
        Spacer(Modifier.height(18.dp))

        ZlCard {
            PermissionRow(
                title = stringResource(R.string.perm_storage),
                desc = stringResource(R.string.perm_storage_desc),
                granted = storageGranted,
                onAction = onStorage
            )
            ZlDivider()
            PermissionRow(
                title = stringResource(R.string.perm_notification),
                desc = stringResource(R.string.perm_notification_desc),
                granted = notificationGranted,
                onAction = onNotification
            )
            ZlDivider()
            PermissionRow(
                title = stringResource(R.string.perm_background),
                desc = stringResource(R.string.perm_background_desc),
                granted = backgroundGranted,
                onAction = onBackground
            )
        }

        Spacer(Modifier.height(16.dp))
        val grantedCount = listOf(storageGranted, notificationGranted, backgroundGranted).count { it }
        Text(
            text = if (grantedCount == 3) {
                stringResource(R.string.perm_granted)
            } else {
                stringResource(R.string.perm_partial, grantedCount, 3)
            },
            style = ZlMonoSmall,
            color = if (grantedCount == 3) ZlAccent else ZlMuted
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.perm_later_hint),
            style = ZlMonoSmall,
            color = ZlMuted
        )
    }
}

@Composable
private fun ContainerStep(
    selected: String,
    onSelect: (String) -> Unit,
    existingContainer: String,
    suggestedId: String,
    creating: Boolean,
    error: String,
    onCreateContainer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 26.dp)
    ) {
        Text(
            text = stringResource(R.string.distro_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZlFg
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.distro_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = ZlMuted
        )
        Spacer(Modifier.height(18.dp))

        DistroCatalog.all.forEach { distro ->
            DistroCard(
                distro = distro,
                selected = distro.key == selected,
                onClick = { onSelect(distro.key) }
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(6.dp))
        if (existingContainer.isNotBlank()) {
            Text(
                text = stringResource(R.string.wizard_container_existing, existingContainer),
                style = ZlMonoSmall,
                color = ZlAccent
            )
        } else if (suggestedId.isNotBlank()) {
            Text(
                text = stringResource(R.string.wizard_container_planned, suggestedId),
                style = ZlMonoSmall,
                color = ZlAccent
            )
        }
        Spacer(Modifier.height(10.dp))
        if (creating) {
            Text(
                text = stringResource(R.string.wizard_container_creating),
                style = ZlMonoSmall,
                color = ZlAccent
            )
            Spacer(Modifier.height(6.dp))
        }
        if (error.isNotBlank()) {
            Text(text = error, style = ZlMonoSmall, color = ZlDanger)
            Spacer(Modifier.height(6.dp))
        }
        ZlGhostButton(
            text = stringResource(R.string.wizard_container_create),
            enabled = !creating,
            onClick = onCreateContainer
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wizard_container_hint),
            style = ZlMonoSmall,
            color = ZlMuted
        )
    }
}

@Composable
private fun ReadyStep(selected: String, suggestedId: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 26.dp)
    ) {
        Text(
            text = stringResource(R.string.wizard_ready_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZlFg
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.wizard_ready_body),
            style = MaterialTheme.typography.bodyMedium,
            color = ZlMuted
        )
        Spacer(Modifier.height(20.dp))

        ZlCard {
            ReadyRow(stringResource(R.string.wizard_ready_distro, selected.ifBlank { "—" }))
            ZlDivider()
            ReadyRow(
                stringResource(
                    R.string.wizard_ready_container,
                    suggestedId.ifBlank { "—" }
                )
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.wizard_ready_hint),
            style = ZlMonoSmall,
            color = ZlMuted
        )
    }
}

@Composable
private fun ReadyRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "●", style = ZlMonoSmall, color = ZlAccent)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = ZlFg
        )
    }
}

@Composable
private fun DistroCard(distro: DistroOption, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) distro.accent else ZlBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ZlSurface else ZlSurface.copy(alpha = 0.6f))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (selected) distro.accent else ZlBorder)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(distro.nameRes),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = ZlMono,
                    fontWeight = FontWeight.Bold
                ),
                color = if (selected) ZlFg else ZlFg.copy(alpha = 0.85f)
            )
            Text(
                text = stringResource(distro.descRes),
                style = ZlMonoSmall,
                color = ZlMuted
            )
        }
        if (selected) {
            Text(text = "●", style = ZlMonoSmall, color = distro.accent)
        }
    }
}

@Composable
private fun WizardBottomBar(step: Int, canContinue: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = ZlBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 给「上一步」一个权重盒，避免大字号下与主按钮挤在一起。
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (step > WizardActivity.STEP_WELCOME) {
                    ZlGhostButton(text = stringResource(R.string.wizard_back), onClick = onBack)
                }
            }
            ZlPrimaryButton(
                text = if (step < WizardActivity.LAST_STEP) {
                    stringResource(R.string.wizard_next)
                } else {
                    stringResource(R.string.wizard_done)
                },
                enabled = canContinue,
                onClick = onNext
            )
        }
    }
}
