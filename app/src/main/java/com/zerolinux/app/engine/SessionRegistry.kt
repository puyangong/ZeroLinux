package com.zerolinux.app.engine

import android.content.Context
import com.zerolinux.app.core.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * `:terminal` 进程内的会话注册表（单例）。
 *
 * 所有可见会话都在这里，UI 里任何列表都不会出现隐藏会话。
 */
object SessionRegistry {

    data class Info(
        val sid: String,
        val containerUuid: String,
        val containerId: String,
        val title: String,
        val pid: Int,
        val running: Boolean
    )

    private val seq = AtomicInteger(0)
    private val sessions = LinkedHashMap<String, TerminalSession>()

    private val _sessions = MutableStateFlow<List<Info>>(emptyList())
    val list: StateFlow<List<Info>> = _sessions.asStateFlow()

    /**
     * 容器重启标记：每次「重启容器」清空会话后自增，并记录是哪个 uuid。
     *
     * 终端页据此判断「当前容器被重启了」，重建一个 shell；普通退出/手动关闭
     * 会话不会触发，避免终端自己又弹出一个新会话。
     */
    private val _restartTick = MutableStateFlow(0L)
    val restartTick: StateFlow<Long> = _restartTick.asStateFlow()

    private val _lastRestartedUuid = MutableStateFlow<String?>(null)
    val lastRestartedUuid: StateFlow<String?> = _lastRestartedUuid.asStateFlow()

    /** 数据出口，由 WebView 宿主挂上/摘下。 */
    @Volatile
    private var sink: ((sid: String, bytes: ByteArray) -> Unit)? = null

    @Volatile
    private var exitSink: ((sid: String, code: Int) -> Unit)? = null

    @Synchronized
    fun attachSink(
        onData: (sid: String, bytes: ByteArray) -> Unit,
        onExit: (sid: String, code: Int) -> Unit
    ) {
        sink = onData
        exitSink = onExit
    }

    @Synchronized
    fun detachSink() {
        sink = null
        exitSink = null
    }

    fun currentSink(): ((sid: String, bytes: ByteArray) -> Unit)? = sink

    fun get(sid: String): TerminalSession? = synchronized(this) { sessions[sid] }

    fun all(): List<TerminalSession> = synchronized(this) { sessions.values.toList() }

    fun visible(): List<TerminalSession> = all().filter { !it.hidden }

    /**
     * 新建一个可见会话。若给的是隐藏容器（不存在的 uuid），直接失败。
     */
    @Synchronized
    fun create(
        ctx: Context,
        containerUuid: String,
        containerId: String,
        cols: Int,
        rows: Int,
        title: String = "shell"
    ): TerminalSession? {
        val sid = "s${seq.incrementAndGet()}"
        val session = TerminalSession(
            sid = sid,
            containerUuid = containerUuid,
            containerId = containerId,
            hidden = false,
            // 新建会话统一去掉标题前缀空格；日志目录名同样保持干净。
            title = title.trimStart()
        )
        session.onData = { bytes ->
            sink?.invoke(sid, bytes)
        }
        session.onExit = { code ->
            exitSink?.invoke(sid, code)
            publish()
        }
        if (!session.start(ctx, cols, rows)) {
            android.util.Log.w("ZlSession", "start failed uuid=$containerUuid (rootfs/proot unavailable)")
            return null
        }
        sessions[sid] = session
        android.util.Log.i("ZlSession", "create sid=$sid uuid=$containerUuid pid=${session.pid}")
        publish()
        return session
    }

    /** 同一容器再开一个会话（`+` 按钮）。 */
    fun createForContainer(ctx: Context, containerUuid: String, containerId: String, cols: Int, rows: Int): TerminalSession? {
        val existing = visible().count { it.containerUuid == containerUuid }
        return create(ctx, containerUuid, containerId, cols, rows, "shell ${existing + 1}")
    }

    @Synchronized
    fun remove(sid: String, kill: Boolean = true) {
        val session = sessions.remove(sid) ?: return
        TerminalLogStore.finish(session)
        if (kill) session.kill()
        publish()
    }

    @Synchronized
    fun removeContainer(containerUuid: String, restart: Boolean = false) {
        val victims = sessions.values.filter { it.containerUuid == containerUuid }
        android.util.Log.i(
            "ZlSession",
            "removeContainer uuid=$containerUuid restart=$restart victims=${victims.map { it.sid }}"
        )
        victims.forEach {
            sessions.remove(it.sid)
            runCatching { it.kill() }
        }
        // 先 publish 空列表，再发 restart tick：终端页收到 tick 后创建的会话
        // 一定排在这次 publish 之后，不会被「空列表」覆盖（曾经因此让抽屉
        // 一直显示旧会话已退出）。
        publish()
        if (restart) {
            _lastRestartedUuid.value = containerUuid
            _restartTick.value = _restartTick.value + 1
        }
    }

    @Synchronized
    fun removeAllVisible() {
        sessions.values.toList().forEach {
            sessions.remove(it.sid)
            runCatching { it.kill() }
        }
        publish()
    }

    @Synchronized
    fun rename(sid: String, title: String) {
        val session = sessions[sid] ?: return
        titles[sid] = title
        session.title = title
        // 日志目录跟着会话名走：旧目录里的 last.txt 与历史日志一起搬到新目录。
        TerminalLogStore.rename(session, title)
        publish()
    }

    /** 会话重启后刷新 pid/running，抽屉里的会话状态需要重新发布。 */
    @Synchronized
    fun refresh() {
        publish()
    }

    private val titles = HashMap<String, String>()

    fun titleOf(sid: String, fallback: String): String = titles[sid] ?: fallback

    /**
     * 生成会话快照。所有改 `sessions` 的方法都持有同一把锁，读侧也必须加锁，
     * 否则 onExit（PTY 读线程）与 create/remove（UI 线程）并发时会发布旧快照，
     * 抽屉里就会闪过/留下「已退出」的幽灵会话。
     */
    @Synchronized
    private fun publish() {
        val snapshot = sessions.values.map {
            Info(
                sid = it.sid,
                containerUuid = it.containerUuid,
                containerId = it.containerId,
                title = titles[it.sid] ?: it.title,
                pid = it.pid,
                running = it.running
            )
        }
        _sessions.value = snapshot
    }

    /** 进程被杀后清理死会话（UI 刷新时调用）。 */
    @Synchronized
    fun pruneDead(): List<TerminalSession> {
        val dead = sessions.values.filter { !it.running }
        dead.forEach { sessions.remove(it.sid) }
        if (dead.isNotEmpty()) publish()
        return dead
    }

    /**
     * 按 /proc 状态回收已死会话。被 `kill -9` 的 proot 会变成僵尸（目录仍在、
     * running 标志也可能来不及翻），只有 stat 里的状态位能可靠判定。
     * UI 轮询调用，保证抽屉不会一直挂着死会话。
     */
    @Synchronized
    fun reapDeadPids(): Int {
        val dead = sessions.values.filter { s ->
            val pid = s.pid
            pid > 0 && runCatching {
                val stat = java.io.File("/proc/$pid/stat").readText()
                stat.substringAfterLast(')').trim().firstOrNull() == 'Z'
            }.getOrDefault(false)
        }
        if (dead.isEmpty()) return 0
        dead.forEach { sessions.remove(it.sid) }
        publish()
        return dead.size
    }

    fun containerDirExists(ctx: Context, uuid: String): Boolean =
        AppPaths.containerRootfs(ctx, uuid).isDirectory
}
