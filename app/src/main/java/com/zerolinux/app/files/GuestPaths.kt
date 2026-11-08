package com.zerolinux.app.files

/**
 * 路径坐标系的换算。文件管理器里同时存在两套路径：
 *
 * - 「SD 相对路径」：以 sdRoot 为根，`/DCIM`。SD 浏览器的 entry.guestPath 就是这个。
 * - 「guest 容器路径」：以 rootfs 为根，`/sdcard/DCIM`。终端里的 cd 只认这个。
 *
 * SD 卡的 guest 侧有 `/sdcard`、`/mnt/sdcard`、`/storage/emulated/0` 三种常见写法，
 * 统一归一到 `/sdcard`，其余路径原样返回（不做 `..` 解析）。
 */
object GuestPaths {

    /** 越长越先匹配，避免 `/sdcard` 抢在 `/storage/emulated/0` 前面。 */
    private val SD_PREFIXES = listOf("/storage/emulated/0", "/mnt/sdcard", "/sdcard")

    private val SLASHES = Regex("/+")

    /** SD 相对路径 → guest 绝对路径。`/`、空串都指向 SD 根。 */
    fun sdToGuest(p: String): String {
        val cleaned = p.replace(SLASHES, "/").trimEnd('/')
        if (cleaned.isEmpty()) return "/sdcard"
        return if (cleaned.startsWith("/")) "/sdcard$cleaned" else "/sdcard/$cleaned"
    }

    /** guest 绝对路径 → SD 相对路径；不属于 SD 卡时返回 null。 */
    fun guestToSd(p: String): String? {
        val normalized = normalizeGuest(p)
        if (normalized == "/sdcard") return "/"
        if (!normalized.startsWith("/sdcard/")) return null
        return normalized.removePrefix("/sdcard")
    }

    /** 把三种 SD 前缀统一成 `/sdcard...`；其他路径只规整斜杠与尾斜杠。 */
    fun normalizeGuest(p: String): String {
        if (p.isBlank()) return ""
        val cleaned = p.replace(SLASHES, "/").let {
            if (it.length > 1) it.trimEnd('/') else it
        }.ifEmpty { "/" }
        for (prefix in SD_PREFIXES) {
            if (cleaned == prefix) return "/sdcard"
            if (cleaned.startsWith("$prefix/")) return "/sdcard" + cleaned.substring(prefix.length)
        }
        return cleaned
    }

    /**
     * 终端日志在 guest 里的根：宿主 /storage/emulated/0/zerolinux/terminal 已被
     * 阶段 AH3 绑到 guest 的 /sdcard，所以终端里走 /sdcard/zerolinux/terminal。
     */
    const val LOG_GUEST_ROOT = "/sdcard/zerolinux/terminal"

    /**
     * 日志区相对路径（日志浏览器以日志根为 /）→ guest 绝对路径。
     *
     * @param containerDirName 容器目录名，必须用 TerminalLogStore.safeName(label) 算出来，
     *   与落盘时保持同一套安全化规则。
     */
    fun logToGuest(containerDirName: String, relative: String): String {
        val head = "$LOG_GUEST_ROOT/$containerDirName"
        val rel = relative.replace(SLASHES, "/").trim('/')
        return if (rel.isEmpty()) head else "$head/$rel"
    }
}
