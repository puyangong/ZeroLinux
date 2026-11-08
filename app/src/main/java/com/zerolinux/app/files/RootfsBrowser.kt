package com.zerolinux.app.files

import android.content.Context
import com.zerolinux.app.core.AppPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * 容器 rootfs 的宿主侧浏览器。
 *
 * 直接读 `filesDir/containers/<uuid>/rootfs` 下的文件，不走 `proot ls`——
 * 后者要 fork 一个 PRoot 进程才能列目录，慢一到两个数量级。
 * 对外一律用 guest 路径（`/` 就是 rootfs 根），只在落到 `java.io.File`
 * 时换成宿主路径。
 */
class RootfsBrowser(
    ctx: Context,
    val uuid: String,
    /** 覆盖浏览根：传 null 时看容器 rootfs，传 SD 卡目录时把该目录当作 `/`。 */
    rootOverride: File? = null
) {

    data class Entry(
        val name: String,
        val guestPath: String,
        val isDir: Boolean,
        val isLink: Boolean,
        val size: Long,
        val mtime: Long
    )

    val rootfs: File = rootOverride ?: AppPaths.containerRootfs(ctx, uuid)

    fun isReady(): Boolean = rootfs.isDirectory

    /** guest 路径规范化：始终以 `/` 开头，去掉尾斜杠与 `..`。 */
    fun normalize(guestPath: String): String {
        val parts = guestPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    fun hostOf(guestPath: String): File {
        val p = normalize(guestPath)
        return if (p == "/") rootfs else File(rootfs, p.removePrefix("/"))
    }

    fun guestOf(file: File): String {
        val rel = runCatching { file.relativeTo(rootfs).invariantSeparatorsPath }.getOrNull()
        return if (rel.isNullOrBlank() || rel == ".") "/" else "/$rel"
    }

    fun parentOf(guestPath: String): String? {
        val p = normalize(guestPath)
        if (p == "/") return null
        val cut = p.lastIndexOf('/')
        return if (cut <= 0) "/" else p.substring(0, cut)
    }

    /**
     * 把符号链接解析成 guest 路径，供点击跳转用。
     *
     * 与 [normalize] 不同：这里保留 `..` 语义，交给 java.nio 的 `normalize()` 折叠。
     * 绝对目标按浏览根解析，相对目标按链接所在目录解析；链接套链接最多跟
     * [MAX_LINK_DEPTH] 层。成环、越出浏览根、无法读取链接时返回 null；
     * 目标不存在但路径本身可解析时照常返回（由 UI 按断链提示）。
     */
    fun resolveLink(guestPath: String): String? {
        var current = normalize(guestPath)
        repeat(MAX_LINK_DEPTH) {
            val target = runCatching { Files.readSymbolicLink(hostOf(current).toPath()) }
                .getOrNull() ?: return null
            val parent = parentOf(current) ?: "/"
            val raw = target.toString().replace('\\', '/')
            val joined = when {
                target.isAbsolute -> raw
                parent == "/" -> "/$raw"
                else -> "$parent/$raw"
            }
            // Paths.normalize() 会把越界的 `..` 静默夹到 `/`，先单独判一次逃逸。
            if (escapesRoot(joined)) return null
            val normalized = runCatching { Paths.get(joined).normalize().toString() }.getOrNull()
                ?: return null
            val resolved = "/" + normalized.replace('\\', '/').trimStart('/')
            if (resolved == "/") return "/"
            if (resolved.startsWith("/..")) return null
            val stillLink = runCatching {
                Files.isSymbolicLink(hostOf(resolved).toPath())
            }.getOrDefault(false)
            if (!stillLink) return resolved
            current = resolved
        }
        // 40 层还没跟完：按成环处理，避免无限循环。
        return null
    }

    /** `..` 是否已经越过浏览根。 */
    private fun escapesRoot(path: String): Boolean {
        var depth = 0
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (depth == 0) return true else depth--
                else -> depth++
            }
        }
        return false
    }

    data class Page(
        val entries: List<Entry>,
        val nextOffset: Int,
        val hasMore: Boolean
    )

    fun list(guestPath: String, showHidden: Boolean = false): List<Entry> =
        listPage(guestPath, showHidden, 0, Int.MAX_VALUE).entries

    /**
     * 分页列目录：先只把名字读出来排序，再对当前页做 stat。
     *
     * 大目录（/usr/bin 等）一次性对每个子项做 isDirectory/canRead/length/
     * lastModified 会明显卡顿；分页后每页只 stat [limit] 项，滚动到底再由
     * UI 追加下一页。
     */
    fun listPage(
        guestPath: String,
        showHidden: Boolean = false,
        offset: Int = 0,
        limit: Int = 80
    ): Page {
        val dir = hostOf(guestPath)
        val children = runCatching { dir.listFiles() }.getOrNull()
            ?: return Page(emptyList(), offset, false)
        val base = normalize(guestPath)
        val names = children.asSequence()
            .mapNotNull { it.name }
            .filter { showHidden || !(it.startsWith(".") || (base == "/" && it in PSEUDO)) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
        val from = offset.coerceIn(0, names.size)
        val to = minOf(from.toLong() + limit.toLong(), names.size.toLong()).toInt()
        val out = ArrayList<Entry>(to - from)
        for (i in from until to) {
            entryOf(dir, base, names[i])?.let(out::add)
        }
        out.sortWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        if (com.zerolinux.app.BuildConfig.DEBUG) {
            android.util.Log.d(
                "ZLFiles",
                "page=$guestPath offset=$offset limit=$limit names=${names.size} " +
                    "entries=${out.size} hasMore=${to < names.size}"
            )
        }
        return Page(out, to, to < names.size)
    }

    private fun entryOf(dir: File, base: String, name: String): Entry? {
        val child = File(dir, name)
        val link = runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
        val isDir = runCatching { child.isDirectory }.getOrDefault(false)
        // 悬空的 guest 绝对路径链接（如 /bin/sh -> /bin/busybox）在宿主侧
        // 指向不存在的位置，canRead() 为 false，但仍要显示出来。
        val readable = link || runCatching { child.canRead() }.getOrDefault(false)
        if (!readable) return null
        return Entry(
            name = name,
            // guest 侧的绝对链接在 proot 里才有意义，宿主侧按原样展示
            guestPath = if (base == "/") "/$name" else "$base/$name",
            isDir = isDir,
            isLink = link,
            size = if (isDir) 0L else runCatching { child.length() }.getOrDefault(0L),
            mtime = runCatching { child.lastModified() }.getOrDefault(0L)
        )
    }

    fun exists(guestPath: String): Boolean = hostOf(guestPath).exists()

    private companion object {
        /** 解析符号链接时最多跟进的层数（挡住链接成环）。 */
        const val MAX_LINK_DEPTH = 40

        /** 默认隐藏的伪文件系统。 */
        val PSEUDO = setOf("proc", "sys", "dev", "system")
    }
}
