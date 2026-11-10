package com.zerolinux.app.opencode

import android.content.Context
import android.util.Log
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.core.AppPaths
import org.json.JSONObject
import java.io.File

/**
 * 把多个容器的 OpenCode 供应商配置汇总到目标容器。
 *
 * opencode 把凭证写在容器自己的 HOME 下（`~/.local/share/opencode/auth.json`），
 * 模型/provider 配置写在 `~/.config/opencode/opencode.json`。每个容器的 rootfs
 * 相互独立，所以在 A 容器里配好的供应商，B 容器看不到。
 *
 * 这里采用「追加 + 对比去重」：
 * - 扫描所有其它容器的同名配置文件；
 * - 目标容器没有的 key 才补进去，递归合并对象；
 * - 同名 provider / 同名凭证保留目标容器自己的值，绝不覆盖已有配置。
 *
 * 这样多个容器可以共享同一套供应商，同时各容器仍能保留自己的差异配置。
 */
object AiProviderShare {

    private const val TAG = "ZlAiProvider"

    /** 相对 rootfs 的配置文件，顺序即合并顺序（先基础配置，后凭证）。 */
    private val CONFIG_FILES = listOf(
        "root/.config/opencode/opencode.json",
        "root/.config/opencode/config.json",
        "root/.local/share/opencode/auth.json",
        "root/.opencode/auth.json"
    )

    /**
     * 把其它容器里已有的供应商配置追加到 [targetUuid] 容器，重复项直接跳过。
     *
     * 只在用户点「共享 AI 供应商配置」时调用；返回新增的配置项数量。
     */
    fun sync(ctx: Context, targetUuid: String): Int {
        var total = 0
        runCatching {
            val targetRoot = AppPaths.containerRootfs(ctx, targetUuid)
            if (!targetRoot.isDirectory) return 0
            val others = ContainerRepo(ctx).readOnlySnapshot().items
                .asSequence()
                .filter { it.uuid != targetUuid }
                .map { AppPaths.containerRootfs(ctx, it.uuid) }
                .filter { it.isDirectory }
                .toList()
            if (others.isEmpty()) return 0
            for (rel in CONFIG_FILES) {
                total += mergeFile(targetRoot, others, rel)
            }
        }.onFailure { Log.w(TAG, "sync providers failed for $targetUuid", it) }
        return total
    }

    private fun mergeFile(targetRoot: File, others: List<File>, rel: String): Int {
        val target = File(targetRoot, rel)
        val merged = if (target.isFile) readObject(target) ?: return 0 else JSONObject()
        var added = 0
        for (root in others) {
            val src = File(root, rel)
            if (!src.isFile) continue
            val other = readObject(src) ?: continue
            added += mergeMissing(merged, other)
        }
        if (added <= 0) return 0
        writeObject(target, merged)
        Log.i(TAG, "append $added entr${if (added == 1) "y" else "ies"} -> $rel")
        return added
    }

    /**
     * 把 [src] 中 [dst] 没有的 key 递归补进去。
     *
     * 返回新增的叶子节点数量；0 表示 [dst] 已经覆盖了 [src] 的全部内容。
     */
    private fun mergeMissing(dst: JSONObject, src: JSONObject): Int {
        var added = 0
        val keys = src.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = src.opt(key) ?: continue
            if (!dst.has(key)) {
                dst.put(key, deepCopy(value))
                added += countLeaves(value)
                continue
            }
            // 双方都是对象时继续按 key 对比；数组和其它类型保持目标容器原值。
            val mine = dst.opt(key)
            if (mine is JSONObject && value is JSONObject) {
                added += mergeMissing(mine, value)
            }
        }
        return added
    }

    /** org.json 的对象可能被后续修改，用字符串往返做深拷贝。 */
    private fun deepCopy(value: Any): Any = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is org.json.JSONArray -> org.json.JSONArray(value.toString())
        else -> value
    }

    private fun countLeaves(value: Any): Int = when (value) {
        is JSONObject -> {
            if (value.length() == 0) 1
            else value.keys().asSequence().sumOf { countLeaves(value.opt(it) ?: return@sumOf 0) }
        }
        is org.json.JSONArray -> value.length().coerceAtLeast(1)
        else -> 1
    }

    private fun readObject(file: File): JSONObject? = runCatching {
        JSONObject(file.readText())
    }.getOrNull()

    private fun writeObject(file: File, obj: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".zl-tmp")
        tmp.writeText(obj.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(obj.toString(2))
                tmp.delete()
            }
        }
    }
}
