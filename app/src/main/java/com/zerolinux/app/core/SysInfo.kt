package com.zerolinux.app.core

import android.os.Process
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 目录体积统计。
 *
 * UI 只显示容器 rootfs 的存储占用，不再显示 pid / 运行内存。
 *
 * 大容器有几十万文件，递归一遍可能几十秒。因此：
 *  - 结果缓存 30 分钟，并且写入容器目录旁的 `.zl-storage`，跨进程/重启复用；
 *  - UI 首帧只调 [peekDirBytes]（纯读缓存，不扫盘）；
 *  - [dirBytes] 只在后台补算，线程优先级降到 BACKGROUND，且同一目录单飞。
 */
object SysInfo {

    /** 体积缓存有效期：装机/装包后体积变化慢，30 分钟足够，避免反复全盘扫。 */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /** proot 的 rootfs 里深度正常不超过十几层，40 只是防御性上限。 */
    private const val MAX_DEPTH = 40

    private const val TAG = "ZlSysInfo"

    /** 跨进程缓存文件名，放在 rootfs 的父目录（容器目录）里。 */
    private const val CACHE_FILE = ".zl-storage"

    private data class Entry(val bytes: Long, val atMs: Long)

    /** key = 绝对路径 */
    private val dirCache = HashMap<String, Entry>()

    /** 正在扫描的目录；同一目录被多个协程请求时只跑一遍。 */
    private val scanning = HashSet<String>()

    /**
     * 只读缓存，不扫盘。给 UI 首帧用：有值立刻显示，没有就显示 `…`。
     *
     * 返回 null 表示「没有可用缓存」，不是「目录为空」。
     */
    fun peekDirBytes(dir: File): Long? {
        val key = dir.absolutePath
        val now = System.currentTimeMillis()
        synchronized(dirCache) {
            dirCache[key]?.let { hit ->
                if (now - hit.atMs in 0 until CACHE_TTL_MS) return hit.bytes
            }
        }
        val disk = readDiskCache(dir) ?: return null
        synchronized(dirCache) { dirCache[key] = disk }
        return disk.bytes
    }

    /**
     * 递归求和目录体积。**不要在主线程序列化调用**，只给 IO 协程/线程用。
     *
     * 返回 `-1` 表示「这个目录根本不可用」（不存在 / 不可读），UI 要显示 `存储 —`；
     * 空目录才返回 `0`（`存储 0 B`）。区分这两者很重要：rootfs 还没解压完时，
     * 把 0 当成真实体积会让用户以为容器是空的。
     */
    fun dirBytes(dir: File): Long {
        if (!dir.exists() || !dir.canRead()) return -1L
        // 缓存 key 用 absolutePath：本进程里 canonicalPath 会把 /data/user/0 解析成
        // /data/data（同一个目录的另一个挂载名），拿它做 key 容易和调用方拼出来的
        // 路径对不上，而且对同一目录可能算出两个 key。
        val key = dir.absolutePath
        val now = System.currentTimeMillis()
        synchronized(dirCache) {
            dirCache[key]?.let { hit ->
                if (now - hit.atMs in 0 until CACHE_TTL_MS) return hit.bytes
            }
            // 单飞：已有线程在扫就直接返回旧值，绝不排队重复扫。
            if (!scanning.add(key)) return dirCache[key]?.bytes ?: -1L
        }

        val prevPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrDefault(0)
        return try {
            // 大目录扫描是纯 IO/CPU 活，降到后台优先级，避免和终端输入抢调度。
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val bytes = sumTree(dir)
            val at = System.currentTimeMillis()
            synchronized(dirCache) { dirCache[key] = Entry(bytes, at) }
            writeDiskCache(dir, Entry(bytes, at))
            Log.i(TAG, "dir ${dir.absolutePath} = $bytes")
            bytes
        } finally {
            runCatching { Process.setThreadPriority(prevPriority) }
            synchronized(dirCache) { scanning.remove(key) }
        }
    }

    /** 清掉缓存（容器重建/删除后调用，保证下一次立刻重新统计）。 */
    fun invalidate(dir: File? = null) {
        synchronized(dirCache) {
            if (dir == null) dirCache.clear() else dirCache.remove(dir.absolutePath)
        }
        if (dir != null) runCatching { cacheFile(dir)?.delete() }
    }

    /** `B / KB / MB / GB`，整数值不带小数点，其余保留一位（`86 MB` / `1.2 GB`）。负数返回 `—`。 */
    fun fmtBytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes == 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        if (unit == 0) return "$bytes B"
        val rounded = Math.round(value * 10.0) / 10.0
        val text = if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
        return "$text ${units[unit]}"
    }

    private fun cacheFile(dir: File): File? = dir.parentFile?.let { File(it, CACHE_FILE) }

    private fun readDiskCache(dir: File): Entry? {
        val f = cacheFile(dir) ?: return null
        return runCatching {
            if (!f.isFile) return null
            val parts = f.readText().trim().split(' ')
            if (parts.size != 2) return null
            val bytes = parts[0].toLong()
            val at = parts[1].toLong()
            if (bytes < 0L) return null
            if (System.currentTimeMillis() - at !in 0 until CACHE_TTL_MS) return null
            Entry(bytes, at)
        }.getOrNull()
    }

    private fun writeDiskCache(dir: File, entry: Entry) {
        val f = cacheFile(dir) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText("${entry.bytes} ${entry.atMs}")
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun sumTree(root: File): Long {
        val rootPath = root.absolutePath.trimEnd('/')
        // rootfs 里 bin → usr/bin、sdcard → /storage/emulated/0 之类都是符号链接：
        // 跟进去会把统计带出容器（甚至绕回自身）。java.io.File 的遍历是跟随链接的，
        // 所以必须自己挡。判重与防环：同一个真实目录只进一次（等价于 du 的 inode 检测）。
        val seen = HashSet<String>()
        // 硬链接去重：l2s 把同一批文件硬链接进多个容器，du 也按 inode 只算一次。
        val counted = HashSet<Long>()
        runCatching { root.canonicalPath }.getOrNull()?.let { seen.add(it) }
        var total = 0L
        root.walkTopDown()
            .onEnter { f ->
                if (f == root) return@onEnter true
                val depth = f.absolutePath.removePrefix(rootPath).count { it == '/' }
                // 深度上限只做防御，正常 rootfs 用不到。
                if (depth > MAX_DEPTH) return@onEnter false
                // 不可读的子目录直接跳过，但顶层 rootfs 必须能算（在上面就放行了）。
                if (!runCatching { f.canRead() }.getOrDefault(false)) return@onEnter false
                if (isSymlink(f)) return@onEnter false
                val real = runCatching { f.canonicalPath }.getOrNull() ?: return@onEnter true
                seen.add(real)
            }
            .forEach { f ->
                val st = runCatching { android.system.Os.lstat(f.absolutePath) }.getOrNull()
                    ?: return@forEach
                val kind = st.st_mode and android.system.OsConstants.S_IFMT
                if (kind == android.system.OsConstants.S_IFLNK) return@forEach
                if (!counted.add(st.st_ino)) return@forEach
                // 口径 = 实际占用（st_blocks 是 512B 单位），跟 du 一致。
                // rootfs 里有稀疏文件，用 length() 会把 2.0 GB 报成 3.1 GB。
                total += st.st_blocks * 512L
            }
        return total
    }

    /**
     * 目录项本身是不是符号链接。
     *
     * 不能用 `canonicalPath != absolutePath` 判断：App 进程里 `/data/user/0/...`
     * 的 canonicalPath 恒为 `/data/data/...`，于是每个真实目录都会被误判成链接、
     * 整棵 rootfs 被跳过（表现为顶栏 `存储 0 B`）。lstat 只看最后一段，才是对的。
     */
    private fun isSymlink(f: File): Boolean = runCatching {
        val st = android.system.Os.lstat(f.absolutePath)
        (st.st_mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    }.getOrDefault(false)
}
