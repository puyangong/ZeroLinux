package com.zerolinux.app.files

import android.content.Context
import com.zerolinux.app.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件管理器滚动位置缓存。落 `filesDir/zerolinux/scroll.json`，
 * 键是 `containerId + guest 路径`，LRU 上限 200 条。
 */
class ScrollMemoStore(ctx: Context) {

    data class Memo(
        val containerId: String,
        val path: String,
        val index: Int,
        val offsetPx: Int,
        val ts: Long
    )

    private val file: File = File(AppPaths.root(ctx), "scroll.json")

    fun get(containerId: String, path: String): Memo? = synchronized(LOCK) {
        read().firstOrNull { it.containerId == containerId && it.path == path }
    }

    fun put(memo: Memo) = synchronized(LOCK) {
        val items = read().filterNot { it.containerId == memo.containerId && it.path == memo.path }
            .toMutableList()
        items.add(memo)
        val trimmed = items.sortedByDescending { it.ts }.take(LIMIT)
        write(trimmed)
    }

    fun clear() = synchronized(LOCK) {
        runCatching { file.delete() }
    }

    private fun read(): List<Memo> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("items") ?: JSONArray()
            val out = ArrayList<Memo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Memo(
                        containerId = o.optString("containerId"),
                        path = o.optString("path"),
                        index = o.optInt("index"),
                        offsetPx = o.optInt("offsetPx"),
                        ts = o.optLong("ts")
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<Memo>) {
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("containerId", it.containerId)
                        .put("path", it.path)
                        .put("index", it.index)
                        .put("offsetPx", it.offsetPx)
                        .put("ts", it.ts)
                )
            }
            val tmp = File(file.parentFile, "scroll.json.tmp")
            tmp.writeText(JSONObject().put("items", arr).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        val LOCK = Any()
        const val LIMIT = 200
    }
}
