package com.zerolinux.app.container

import android.content.Context
import com.zerolinux.app.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 容器元数据的唯一写者。`containers.json` 是全量库，
 * `containers/<uuid>/container.json` 是给 `:terminal` 进程直接读取的镜像副本。
 */
class ContainerRepo(private val ctx: Context) {

    data class Snapshot(val active: String?, val items: List<ContainerConfig>)

    private val root: File get() = AppPaths.root(ctx)
    private val lockFile: File get() = File(root, "containers.lock")
    private val dbFile: File get() = File(root, "containers.json")

    fun snapshot(): Snapshot = withLock { read() }

    fun all(): List<ContainerConfig> = snapshot().items

    /**
     * 不加文件锁的只读快照，给 [com.zerolinux.app.providers.DocumentsProvider]
     * 这类「随时会被系统回调」的只读方用。
     *
     * 写端是 `containers.json.tmp` + `renameTo` 的原子替换，所以无锁读要么拿到
     * 旧版本、要么拿到新版本，不会读到半个文件；而 [withLock] 用
     * `RandomAccessFile(...).channel.lock()`，`:terminal` 进程持锁期间系统回调
     * queryRoots 就会被卡住，DocumentsUI 会把本 provider 判定为无响应并丢掉全部 root。
     */
    fun readOnlySnapshot(): Snapshot = read()

    fun activeUuid(): String? = snapshot().active

    fun active(): ContainerConfig? {
        val s = snapshot()
        return s.items.firstOrNull { it.uuid == s.active } ?: s.items.firstOrNull()
    }

    fun find(uuid: String): ContainerConfig? = snapshot().items.firstOrNull { it.uuid == uuid }

    fun findById(id: String): ContainerConfig? = snapshot().items.firstOrNull { it.id == id }

    fun create(cfg: ContainerConfig): ContainerConfig = withLock {
        val s = read()
        require(s.items.none { it.uuid == cfg.uuid }) { "uuid 已存在" }
        require(s.items.none { it.id == cfg.id }) { "容器 ID 已存在" }
        val next = s.copy(
            active = s.active ?: cfg.uuid,
            items = s.items + cfg
        )
        write(next)
        cfg
    }

    fun save(cfg: ContainerConfig): ContainerConfig = withLock {
        val s = read()
        val items = s.items.toMutableList()
        val idx = items.indexOfFirst { it.uuid == cfg.uuid }
        if (idx >= 0) {
            items[idx] = cfg
        } else {
            // 多进程下 :ai 可能正持有旧配置（例如 ensurePort 刚分配完端口），
            // 而主进程已经删除容器。容器目录是删除时最先清掉的，目录不存在
            // 就说明这是一次迟到的 save，不能再把配置写回列表。
            if (!AppPaths.containerDir(ctx, cfg.uuid).isDirectory) return@withLock cfg
            items.add(cfg)
        }
        write(s.copy(items = items))
        cfg
    }

    /**
     * 跨进程安全的字段级更新：在文件锁内重新读取最新配置再修改，
     * 避免 `:ai` 用旧快照整条覆盖主进程刚写入的密码/设置。
     * 容器已被删除时返回 null，绝不把配置重新加回列表。
     */
    fun update(uuid: String, transform: (ContainerConfig) -> ContainerConfig): ContainerConfig? = withLock {
        val s = read()
        val items = s.items.toMutableList()
        val idx = items.indexOfFirst { it.uuid == uuid }
        if (idx < 0) return@withLock null
        val updated = transform(items[idx])
        items[idx] = updated
        write(s.copy(items = items))
        updated
    }

    /** 只改可见 ID，目录名不动。返回错误文案或 null。 */
    fun renameId(uuid: String, newId: String): String? = withLock {
        val trimmed = newId.trim()
        if (!trimmed.matches(ID_RE)) return@withLock "ID 只能包含字母、数字、点、下划线和短横线（1-32 位）"
        val s = read()
        if (s.items.any { it.id == trimmed && it.uuid != uuid }) return@withLock "ID 已被其它容器使用"
        val items = s.items.map { if (it.uuid == uuid) it.copy(id = trimmed) else it }
        write(s.copy(items = items))
        null
    }

    fun delete(uuid: String): Unit = withLock {
        val s = read()
        val items = s.items.filterNot { it.uuid == uuid }
        val active = if (s.active == uuid) items.firstOrNull()?.uuid else s.active
        write(s.copy(active = active, items = items))
        AppPaths.containerJson(ctx, uuid).delete()
    }

    fun setActive(uuid: String): Unit = withLock {
        val s = read()
        if (s.items.none { it.uuid == uuid }) return@withLock
        write(s.copy(active = uuid))
    }

    fun suggestId(distro: DistroId): String = withLock {
        val s = read()
        var n = 1
        while (s.items.any { it.id == "${distro.key}-$n" }) n++
        "${distro.key}-$n"
    }

    fun writeSidecar(cfg: ContainerConfig) {
        val dir = AppPaths.containerDir(ctx, cfg.uuid)
        if (!dir.exists()) dir.mkdirs()
        val f = AppPaths.containerJson(ctx, cfg.uuid)
        val tmp = File(dir, "container.json.tmp")
        runCatching {
            tmp.writeText(cfg.toJson().toString(2))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    // ---- 内部 ----

    private fun read(): Snapshot {
        val f = dbFile
        if (!f.exists()) return Snapshot(null, emptyList())
        val text = runCatching { f.readText() }.getOrNull() ?: return Snapshot(null, emptyList())
        return runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("containers") ?: JSONArray()
            val list = ArrayList<ContainerConfig>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o ->
                    val cfg = ContainerConfig.fromJson(o)
                    // 旧版本在可选步骤（go/rust）超时后会把 initStep 留在 4/5，
                    // 但 initialized 已经是 true。已完成初始化的容器不再显示续跑步骤。
                    list.add(
                        if (cfg.initialized && cfg.initStep.isNotBlank()) {
                            cfg.copy(initStep = "")
                        } else {
                            cfg
                        }
                    )
                }
            }
            val active = o.optString("active").takeIf { it.isNotBlank() }
            Snapshot(active, list)
        }.getOrElse {
            runCatching { f.renameTo(File(f.parentFile, "containers.json.bad")) }
            Snapshot(null, emptyList())
        }
    }

    private fun write(s: Snapshot) {
        root.mkdirs()
        val o = JSONObject()
            .put("version", DB_VERSION)
            .put("active", s.active ?: "")
        val arr = JSONArray()
        s.items.forEach { arr.put(it.toJson()) }
        o.put("containers", arr)

        val tmp = File(root, "containers.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(dbFile)) {
            dbFile.delete()
            tmp.renameTo(dbFile)
        }
        s.items.forEach { writeSidecar(it) }
    }

    private fun <T> withLock(block: () -> T): T = synchronized(LOCK) {
        root.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    private companion object {
        val LOCK = Any()
        const val DB_VERSION = 1
        val ID_RE = Regex("^[A-Za-z0-9._-]{1,32}$")
    }
}
