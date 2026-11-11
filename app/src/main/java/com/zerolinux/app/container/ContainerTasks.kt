package com.zerolinux.app.container

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zerolinux.app.R
import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.opencode.AppStateStore
import com.zerolinux.app.service.ContainerTaskService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 容器任务队列。所有重活（下载、解压、装包、克隆、备份）都排成一条队，
 * 保证同一时刻只有一个容器在跑，避免几个 apt 同时抢 IO 和带宽。
 *
 * 队列仍然是串行的（[mutex]），但**可视化**是多条的：
 * [_tasks] 里保留每一条入队记录（运行中 / 排队中 / 刚结束），
 * UI 可以同时看到「克隆 A」「创建 B」「初始化 C」各自的状态。
 *
 * [state] 保留为「最近变化的那一条」，兼容通知栏与老的单条 UI 调用方。
 */
private const val TAG = "ContainerTasks"

object ContainerTasks {

    enum class Kind { CREATE, INIT, CLONE, DELETE, BACKUP, RESTORE, RESTART }

    data class State(
        val kind: Kind,
        val containerId: String,
        val progress: ContainerOps.Progress? = null,
        val running: Boolean = true,
        val message: String = "",
        val error: String? = null,
        /** 任务自增 id；同一容器的并发任务靠它区分，不能靠 kind+label。 */
        val id: Long = 0L,
        /** true = 已入队但还没拿到执行锁（前面还有任务在跑）。 */
        val queued: Boolean = false,
        /** 关联容器 uuid。CREATE 在落库前未知，为 null。 */
        val uuid: String? = null
    )

    /** 最多保留几条已结束的任务，避免列表无限增长。 */
    private const val MAX_FINISHED = 3

    /** 结束后在 UI 上多留一会儿，用户才看得到结果。 */
    private const val KEEP_FINISHED_MS = 3_000L

    private val _tasks = MutableStateFlow<List<State>>(emptyList())

    /** 全部任务（排队中 + 运行中 + 最近结束的），按入队顺序。 */
    val tasks: StateFlow<List<State>> = _tasks.asStateFlow()

    private val _state = MutableStateFlow<State?>(null)

    /** 最近一次变化的任务。兼容通知栏/旧 UI 的单条入口。 */
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * 正在删除的容器 uuid。删除可能要等运行中的任务退出（最多 12 秒），
     * 期间列表行/设置页必须显示「删除中…」，不能让用户以为没点上。
     */
    private val _deleting = MutableStateFlow<Set<String>>(emptySet())
    val deleting: StateFlow<Set<String>> = _deleting.asStateFlow()

    private val nextTaskId = AtomicLong(1L)

    /**
     * 调度：同一容器的任务必须串行，不同容器最多并行 [MAX_PARALLEL] 个。
     *
     * 旧实现是一把全局 Mutex，任何一个容器安装慢/卡住都会把后面所有容器
     * 堵死；删除一个正在跑的任务后，后面的任务也只能等它退出。现在换成
     * 「容器锁 + 全局并发槽位」：删除/取消只影响该容器，其它容器继续跑。
     */
    private const val MAX_PARALLEL = 2

    private val slots = Semaphore(MAX_PARALLEL)
    private val containerLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun lockFor(owner: String): Mutex =
        containerLocks.computeIfAbsent(owner) { Mutex() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastContext: Context? = null

    /**
     * 跨进程任务状态写入信号。进度回调可能每几十毫秒一次，不能每次都落盘；
     * 这里用 conflated channel 合并成最多 150ms 一次写。
     */
    private val publishSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in publishSignal) {
                delay(150L)
                val ctx = lastContext ?: continue
                runCatching { TaskStateStore.write(ctx, _tasks.value) }
            }
        }
    }

    /** 任务 id -> 协程 Job，删除容器时要 cancel 正在跑/排队的任务。 */
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /**
     * 已排队/正在跑的任务键（`KIND:uuid|label`）。
     * 同一容器的同种任务重复入队只会把用户的操作放大成两倍耗时的重复劳动
     * （logcat 里见过 4 秒内两次 enqueue INIT），所以入队前先认领一次。
     */
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 认领成功返回 key（unclaimed 时返回空串），重复请求返回 null。 */
    private fun claim(kind: Kind, owner: String?): String? {
        if (owner == null) return ""
        val key = "${kind.name}:$owner"
        if (inflight.add(key)) return key
        Log.i(TAG, "skip duplicate ${kind.name} $owner")
        return null
    }

    /** CREATE 的 body 里已经包含一次 initialize，只跳过同一容器的重复 INIT。 */
    private fun coveredByCreate(label: String): Boolean =
        inflight.contains("${Kind.CREATE.name}:$label")

    /** 只要还有排队中/运行中的任务，就必须保持前台服务存活。 */
    fun isBusy(): Boolean =
        _tasks.value.any { it.running } || _deleting.value.isNotEmpty()

    /** 某个容器是否有 CREATE/INIT 在排队或运行（列表行禁用「初始化」按钮用）。 */
    fun isRunningFor(uuid: String, label: String): Boolean =
        _tasks.value.any { t ->
            t.running && (t.uuid == uuid || (t.uuid == null && t.containerId == label))
        }

    /** 配置发生变化，让列表/设置页立即重读 containers.json。 */
    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /**
     * 通知跨进程状态文件更新。真正的写盘被 [publishSignal] 合并，避免
     * 下载进度每几十毫秒触发一次文件锁 + fsync。
     */
    private fun publishTaskState() {
        if (lastContext == null) return
        publishSignal.trySend(Unit)
    }

    // ---------------------------------------------------------------- 状态维护

    /** 插入或按 id 覆盖一条任务，并同步「最近一条」。 */
    private fun upsert(task: State) {
        var stored = task
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.id == task.id }
            val next = if (idx >= 0) {
                list.toMutableList().also { it[idx] = task }
            } else {
                (list + task).let { trimFinished(it) }
            }
            next
        }
        stored = _tasks.value.firstOrNull { it.id == task.id } ?: task
        _state.value = stored
        publishTaskState()
    }

    /** 只改某一条任务；id 已被清掉时静默忽略。 */
    private fun updateTask(id: Long, block: (State) -> State) {
        var changed: State? = null
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@update list
            val next = list.toMutableList()
            next[idx] = block(next[idx])
            changed = next[idx]
            next
        }
        changed?.let { _state.value = it }
        if (changed != null) publishTaskState()
    }

    /** 已结束的条目最多留 [MAX_FINISHED] 条，运行/排队中的永远不丢。 */
    private fun trimFinished(list: List<State>): List<State> {
        val finished = list.filter { !it.running }
        if (finished.size <= MAX_FINISHED) return list
        val drop = finished.take(finished.size - MAX_FINISHED).map { it.id }.toSet()
        return list.filterNot { it.id in drop }
    }

    private fun scheduleRemove(id: Long) {
        scope.launch {
            delay(KEEP_FINISHED_MS)
            _tasks.update { list -> list.filterNot { it.id == id && !it.running } }
            if (_state.value?.id == id && _state.value?.running != true) {
                _state.value = _tasks.value.lastOrNull { it.running }
                    ?: _tasks.value.lastOrNull()
            }
            publishTaskState()
        }
    }

    // ------------------------------------------------------------------- 入队

    private fun enqueue(
        ctx: Context,
        kind: Kind,
        label: String,
        uuid: String? = null,
        body: suspend (ContainerOps, (ContainerOps.Progress) -> Unit) -> String
    ) {
        val claimKey = claim(kind, uuid ?: label.takeIf { kind == Kind.CREATE })
        if (claimKey == null) return
        if (kind == Kind.INIT && coveredByCreate(label)) {
            claimKey.let { if (it.isNotEmpty()) inflight.remove(it) }
            Log.i(TAG, "skip duplicate INIT ${uuid ?: label} (covered by CREATE)")
            return
        }
        lastContext = ctx.applicationContext
        Log.i(TAG, "enqueue ${kind.name} '$label' uuid=$uuid")

        val taskId = nextTaskId.getAndIncrement()
        val queued = State(
            kind = kind,
            containerId = label,
            running = true,
            queued = true,
            id = taskId,
            uuid = uuid
        )
        // 先把状态置为 running，再拉起前台服务：服务 onCreate 里立刻 startForeground，
        // 若任务秒失败也不会因为 finally 里的 stopService 把尚未 startForeground 的服务拆掉
        // （那会触发 ForegroundServiceDidNotStartInTimeException 并杀掉 :main）。
        upsert(queued)
        ContainerTaskService.start(ctx.applicationContext)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                lockFor(label).withLock {
                    slots.withPermit {
                        updateTask(taskId) { it.copy(queued = false) }
                        val ops = ContainerOps(ctx.applicationContext)
                        try {
                            val message = body(ops) { p ->
                                updateTask(taskId) { it.copy(progress = p) }
                            }
                            Log.i(TAG, "task ${kind.name} '$label' done: $message")
                            updateTask(taskId) {
                                it.copy(running = false, queued = false, message = message, error = null)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            val err = t.message ?: t.javaClass.simpleName
                            Log.e(TAG, "task ${kind.name} '$label' failed: $err", t)
                            // 失败不能只闪一条 1.5s 的横幅：落库 lastError，列表行会一直显示原因并可重试。
                            // 容器可能已被用户在安装中途删除：先确认还存在，避免 repo.save 把它加回来。
                            if (uuid != null) runCatching {
                                val repo = ContainerRepo(ctx.applicationContext)
                                repo.update(uuid) { it.copy(lastError = err) }
                            }
                            updateTask(taskId) {
                                it.copy(running = false, queued = false, message = "", error = err)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "task ${kind.name} '$label' canceled")
                updateTask(taskId) {
                    it.copy(
                        running = false,
                        queued = false,
                        message = ctx.getString(R.string.container_task_canceled),
                        error = null
                    )
                }
                throw e
            } finally {
                if (claimKey.isNotEmpty()) inflight.remove(claimKey)
                bumpRevision()
                scheduleRemove(taskId)
                // 不在这里 stopService：前台服务由自己的 watcher 在空闲 1.2s 后停止。
            }
        }
        jobs[taskId] = job
        job.invokeOnCompletion { jobs.remove(taskId) }
        job.start()
    }

    fun create(
        ctx: Context,
        distro: DistroId,
        id: String?,
        /**
         * 默认 true：rootfs 下载/解压完成后继续初始化。保留参数是给
         * 「只建实例、稍后手动初始化」的特殊流程用。
         */
        initialize: Boolean = true
    ) = enqueue(ctx, Kind.CREATE, id ?: distro.key) { ops, progress ->
        val cfg = ops.create(distro, id, progress)
        // rootfs 已落地（rootfsReady=true）：先让列表/终端重读配置，不必等
        // bootstrap 跑完，更不必重启 App。
        bumpRevision()
        if (initialize) ops.initialize(cfg.uuid, progress).id else cfg.id
    }

    fun createFromArchive(
        ctx: Context,
        distro: DistroId,
        archive: Uri,
        id: String?
    ) = enqueue(ctx, Kind.CREATE, id ?: distro.key) { ops, progress ->
        val imported = ops.importArchive(distro, archive, id, progress)
        bumpRevision()
        // 备份包已经包含完整的 initialized 状态，再跑一遍初始化会把镜像源、
        // 已装好的工具链白白重配一次；只有普通 rootfs 才继续初始化。
        if (imported.restored) {
            imported.config.id
        } else {
            ops.initialize(imported.config.uuid, progress).id
        }
    }

    /** 列表行/设置页直接传配置对象，省得调用方拆字段。 */
    fun initialize(ctx: Context, cfg: ContainerConfig) = initialize(ctx, cfg.uuid, cfg.label)

    fun initialize(ctx: Context, uuid: String, label: String) =
        enqueue(ctx, Kind.INIT, label, uuid) { ops, progress ->
            // 幂等：rootfs 已就绪只补骨架；没落地（下载/解压被打断）先补下载。
            // 不补的话 ops.initialize() 会立刻抛 "rootfs missing"，UI 只闪一下横幅，
            // 用户看到的就是「点了初始化没反应」。
            ops.ensureRootfs(uuid, progress)
            bumpRevision()
            ops.initialize(uuid, progress).id
        }

    fun clone(ctx: Context, uuid: String, newId: String?, label: String) =
        enqueue(ctx, Kind.CLONE, label) { ops, progress -> ops.clone(uuid, newId, progress).id }

    /**
     * 重启容器：停掉该容器所有 PRoot/子进程与终端会话，再重新拉起 shell 与 AI。
     * 与 INIT 共用容器锁，避免和正在跑的安装任务互相踩。
     */
    fun restart(ctx: Context, uuid: String, label: String) =
        enqueue(ctx, Kind.RESTART, label, uuid) { ops, progress ->
            ops.restart(uuid, progress).id
        }

    /**
     * 立即删除容器：先取消该容器正在跑/排队的 CREATE/INIT/CLONE 任务，
     * 等任务退出后再删目录，避免下载线程继续往已删除的 rootfs 里写。
     * 不排进 [mutex] 队列，否则「安装卡住时删除」会一直等安装结束。
     */
    fun delete(ctx: Context, uuid: String, label: String): Job {
        lastContext = ctx.applicationContext
        // 先置「删除中」：列表行和设置页会立刻显示状态，而不是等 12 秒 join 完才消失。
        _deleting.update { it + uuid }
        return scope.launch {
            try {
                val victims = _tasks.value.filter {
                    it.running && (it.uuid == uuid || it.containerId == label)
                }
                victims.forEach { jobs[it.id]?.cancel() }
                // 给取消留出收尾时间；下载/解压/proot 都有取消检查，正常几百毫秒内退出。
                withTimeoutOrNull(12_000L) {
                    victims.forEach { jobs[it.id]?.join() }
                }
                val ids = victims.map { it.id }.toSet()
                // 排队中的 LAZY job 可能在启动前就被取消，body/finally 不会执行，
                // 这里手动释放 claim，避免同一容器以后再也排不进同类任务。
                victims.forEach { t ->
                    val owner = t.uuid ?: t.containerId
                    inflight.remove("${t.kind.name}:$owner")
                }
                if (ids.isNotEmpty()) {
                    _tasks.update { list -> list.filterNot { it.id in ids } }
                    _state.value = _tasks.value.lastOrNull { it.running }
                        ?: _tasks.value.lastOrNull()
                    publishTaskState()
                }
                // 容器没了，AI 客户端里的服务器条目/token 也要一起消失，
                // 否则 AAR 服务器列表会留下一个永远连不上的僵尸地址。
                // AAR 与 opencode serve 都在 :ai 进程，本进程只发命令；
                // 没分配过端口说明 AAR 里不会有条目，不必多启一次服务。
                runCatching {
                    val app = ctx.applicationContext
                    if (AppStateStore.lastAiUuid(app) == uuid) {
                        AppStateStore.clearLastAiUuid(app)
                    }
                    val cfg = ContainerRepo(app).find(uuid)
                    if (cfg != null && cfg.opencodePort > 0) {
                        AiIntents.forget(app, uuid, cfg.opencodePort)
                    }
                }
                runCatching { ContainerOps(ctx.applicationContext).delete(uuid) }
                    .onFailure { Log.e(TAG, "delete '$label' failed", it) }
                Log.i(TAG, "delete '$label' uuid=$uuid after canceling ${victims.size} task(s)")
            } finally {
                _deleting.update { it - uuid }
                bumpRevision()
                // 删除完成后任务列表可能已空，但 tasks 不会再发一次空列表；
                // 主动收一次前台服务，避免通知常驻。
                ContainerTaskService.stop(ctx.applicationContext)
            }
        }
    }

    fun backup(ctx: Context, uuid: String, target: Uri, label: String) =
        enqueue(ctx, Kind.BACKUP, label, uuid) { ops, progress ->
            ops.backup(uuid, target, progress); label
        }

    fun restore(ctx: Context, source: Uri) =
        enqueue(ctx, Kind.RESTORE, "restore") { ops, progress -> ops.restore(source, progress).id }

    /** 清掉所有已结束的条目；运行中/排队中的保留。 */
    fun clearFinished() {
        _tasks.update { list -> list.filter { it.running } }
        _state.value = _tasks.value.lastOrNull { it.running } ?: _tasks.value.lastOrNull()
    }
}
