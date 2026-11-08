package com.zerolinux.app.files

import android.content.Context
import com.zerolinux.app.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 文件管理器里的「快捷方式」。真正的软链已经由 [FileOps] 建在 rootfs 内，
 * 这里只记一笔账，好在文件管理器顶部集中展示、并在容器删除时一起清掉。
 */
class ShortcutStore(private val ctx: Context) {

    data class Shortcut(
        val id: String,
        val containerId: String,
        /** 软链所在的 guest 路径，例如 `/root/deploy`。 */
        val linkGuestPath: String,
        /** 指向的原文件/目录 guest 路径。 */
        val targetGuestPath: String,
        val name: String,
        val createdAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("containerId", containerId)
            .put("linkGuestPath", linkGuestPath)
            .put("targetGuestPath", targetGuestPath)
            .put("name", name)
            .put("createdAt", createdAt)

        companion object {
            fun fromJson(o: JSONObject) = Shortcut(
                id = o.optString("id"),
                containerId = o.optString("containerId"),
                linkGuestPath = o.optString("linkGuestPath"),
                // 旧版本可能把 SD 目标写成 /storage/emulated/0/... 或 /mnt/sdcard/...，
                // 读出来时幂等归一成 /sdcard/...，不改磁盘上的 JSON 格式。
                targetGuestPath = GuestPaths.normalizeGuest(o.optString("targetGuestPath")),
                name = o.optString("name"),
                createdAt = o.optLong("createdAt")
            )
        }
    }

    private val file: File get() = File(AppPaths.root(ctx), "shortcuts.json")

    private fun read(): MutableList<Shortcut> {
        if (!file.exists()) return ArrayList()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("items") ?: JSONArray()
            ArrayList<Shortcut>(arr.length()).also { list ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(Shortcut.fromJson(it)) }
                }
            }
        }.getOrDefault(ArrayList())
    }

    private fun write(items: List<Shortcut>) {
        val o = JSONObject()
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        o.put("items", arr)
        val tmp = File(file.parentFile, "shortcuts.json.tmp")
        runCatching {
            file.parentFile?.mkdirs()
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun all(containerId: String): List<Shortcut> =
        synchronized(LOCK) { read().filter { it.containerId == containerId }.sortedBy { it.createdAt } }

    fun add(shortcut: Shortcut): Shortcut = synchronized(LOCK) {
        val items = read()
        // SD 快捷方式没有容器内软链（linkGuestPath 为空），只能按目标路径去重；
        // 容器快捷方式仍按软链位置去重，保持原有行为。
        items.removeAll {
            it.containerId == shortcut.containerId &&
                if (shortcut.linkGuestPath.isEmpty()) it.targetGuestPath == shortcut.targetGuestPath
                else it.linkGuestPath == shortcut.linkGuestPath
        }
        items.add(shortcut)
        write(items)
        shortcut
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun remove(id: String) = synchronized(LOCK) {
        val items = read()
        items.removeAll { it.id == id }
        write(items)
    }

    fun removeForContainer(containerId: String) = synchronized(LOCK) {
        val items = read()
        items.removeAll { it.containerId == containerId }
        write(items)
    }

    private companion object {
        val LOCK = Any()
    }
}
