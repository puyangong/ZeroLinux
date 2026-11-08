package com.zerolinux.app.container

import android.content.Context
import android.os.Process
import com.zerolinux.app.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 跨进程容器任务状态。
 *
 * 容器任务只在主进程的 [ContainerTasks] 里执行；`:terminal` 进程不能直接读
 * 主进程的内存 StateFlow。主进程把当前任务列表原子写入这个 JSON 文件，
 * 终端页按固定间隔读取，就能继续显示下载/解压/初始化/重启的进度。
 *
 * 文件里带 writer pid。主进程被系统杀掉后，旧文件不会被终端误显示：
 * 读取时会检查该 pid 是否仍然是本应用的主进程。
 */
object TaskStateStore {

    private const val FILE_NAME = "container-task.json"
    private const val LOCK_NAME = "container-task.lock"
    private const val VERSION = 1

    private val LOCK = Any()

    fun write(ctx: Context, tasks: List<ContainerTasks.State>) {
        val app = ctx.applicationContext
        val root = AppPaths.root(app)
        runCatching { root.mkdirs() }
        val target = File(root, FILE_NAME)
        val lockFile = File(root, LOCK_NAME)

        val json = JSONObject()
            .put("version", VERSION)
            .put("pid", Process.myPid())
            .put("updated", System.currentTimeMillis())
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        json.put("tasks", arr)

        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val tmp = File(root, "$FILE_NAME.${Process.myPid()}.${System.nanoTime()}.tmp")
        synchronized(LOCK) {
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    try {
                        FileOutputStream(tmp).use { out ->
                            out.write(bytes)
                            out.fd.sync()
                        }
                        if (!tmp.renameTo(target)) {
                            // 极少数文件系统不支持同目录 rename：退化为覆盖写，但仍持锁。
                            FileOutputStream(target).use { out ->
                                out.write(bytes)
                                out.fd.sync()
                            }
                        }
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                }
            }
        }
    }

    fun read(ctx: Context): List<ContainerTasks.State> {
        val file = File(AppPaths.root(ctx.applicationContext), FILE_NAME)
        if (!file.isFile) return emptyList()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        if (json.optInt("version", 0) != VERSION) return emptyList()
        if (!writerAlive(ctx, json.optInt("pid", 0))) return emptyList()
        val arr = json.optJSONArray("tasks") ?: return emptyList()
        val out = ArrayList<ContainerTasks.State>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parse(o)?.let { out.add(it) }
        }
        return out
    }

    /** 主进程还活着才认这份状态，避免崩溃/被杀后残留横幅一直显示。 */
    private fun writerAlive(ctx: Context, pid: Int): Boolean {
        if (pid <= 0) return false
        val bytes = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull()
            ?: return false
        if (bytes.isEmpty()) return false
        val name = String(bytes, Charsets.UTF_8).trimEnd { it == '\u0000' || it.isWhitespace() }
        return name == ctx.packageName
    }

    private fun ContainerTasks.State.toJson(): JSONObject {
        val o = JSONObject()
            .put("kind", kind.name)
            .put("containerId", containerId)
            .put("running", running)
            .put("message", message)
            .put("id", id)
            .put("queued", queued)
        if (error != null) o.put("error", error)
        if (uuid != null) o.put("uuid", uuid)
        progress?.let { p ->
            o.put(
                "progress",
                JSONObject()
                    .put("stage", p.stage.name)
                    .put("step", p.step)
                    .put("total", p.total)
                    .put("detail", p.detail)
                    .put("subPercent", p.subPercent)
            )
        }
        return o
    }

    private fun parse(o: JSONObject): ContainerTasks.State? {
        val kind = runCatching {
            ContainerTasks.Kind.valueOf(o.optString("kind"))
        }.getOrNull() ?: return null
        val progress = o.optJSONObject("progress")?.let { p ->
            val stage = runCatching {
                ContainerOps.Stage.valueOf(p.optString("stage"))
            }.getOrNull() ?: return@let null
            ContainerOps.Progress(
                stage = stage,
                step = p.optInt("step", 0),
                total = p.optInt("total", 0),
                detail = p.optString("detail", ""),
                subPercent = p.optInt("subPercent", -1)
            )
        }
        return ContainerTasks.State(
            kind = kind,
            containerId = o.optString("containerId", ""),
            progress = progress,
            running = o.optBoolean("running", false),
            message = o.optString("message", ""),
            error = if (o.has("error") && !o.isNull("error")) o.optString("error") else null,
            id = o.optLong("id", 0L),
            queued = o.optBoolean("queued", false),
            uuid = o.optString("uuid", "").takeIf { it.isNotBlank() }
        )
    }
}
