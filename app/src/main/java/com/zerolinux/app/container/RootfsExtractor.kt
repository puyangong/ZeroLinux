package com.zerolinux.app.container

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * rootfs 解压。用 commons-compress 自己解，不依赖设备上的 tar/xz 命令
 * （Android 的 toybox tar 没有 xz 支持）。
 */
object RootfsExtractor {

    suspend fun extract(
        archive: File,
        targetDir: File,
        /**
         * 备份包里的条目都带 `rootfs/` 前缀，但 guest 里的绝对路径没有这一层。
         * 硬链接退回符号链接时要按去掉前缀后的路径指，否则链接会指向 guest 里
         * 并不存在的 `/rootfs/...`。
         */
        hardlinkGuestPrefix: String = "",
        onProgress: (bytes: Long) -> Unit = {}
    ) {
        targetDir.mkdirs()
        val dirModes = ArrayList<Pair<File, Int>>(512)
        val fileModes = ArrayList<Pair<File, Int>>(4096)

        openStream(archive).use { raw ->
            val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 256 * 1024)
            TarArchiveInputStream(buffered).use { tar ->
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val name = sanitize(entry.name) ?: continue
                    val out = File(targetDir, name)

                    if (entry.isDirectory) {
                        out.mkdirs()
                        dirModes.add(out to entry.mode)
                        continue
                    }
                    out.parentFile?.mkdirs()

                    when {
                        entry.isSymbolicLink -> {
                            runCatching { out.delete() }
                            runCatching { Os.symlink(entry.linkName, out.absolutePath) }
                        }

                        entry.isLink -> {
                            // Android 明确禁止在应用数据目录建硬链接（ln: Permission denied），
                            // 硬链接回退绝不能复制数据：Ubuntu 26.04 的 rust-coreutils 一个
                            // 10.5MB 多调用二进制被上百个命令硬链接，复制会撑到 1.2GB 且丢执行位。
                            // 改用指向 guest 根目录的绝对符号链接，语义足够且零成本。
                            val linkTarget = sanitize(entry.linkName)
                            if (linkTarget != null) {
                                runCatching { out.delete() }
                                val ok = runCatching {
                                    Os.link(File(targetDir, linkTarget).absolutePath, out.absolutePath)
                                }.isSuccess
                                if (!ok) {
                                    val guestTarget = if (
                                        hardlinkGuestPrefix.isNotEmpty() &&
                                        linkTarget.startsWith(hardlinkGuestPrefix)
                                    ) {
                                        linkTarget.removePrefix(hardlinkGuestPrefix)
                                    } else {
                                        linkTarget
                                    }
                                    val symlinkTarget =
                                        if (entry.linkName.startsWith("/")) entry.linkName
                                        else "/$guestTarget"
                                    if (runCatching { Os.symlink(symlinkTarget, out.absolutePath) }.isFailure) {
                                        runCatching { File(targetDir, linkTarget).copyTo(out, overwrite = true) }
                                        fileModes.add(out to entry.mode)
                                    }
                                }
                            }
                        }

                        entry.isFile -> {
                            FileOutputStream(out).use { fos ->
                                val buf = ByteArray(256 * 1024)
                                var remaining = entry.size
                                while (remaining > 0) {
                                    currentCoroutineContext().ensureActive()
                                    val want = minOf(remaining, buf.size.toLong()).toInt()
                                    val n = tar.read(buf, 0, want)
                                    if (n <= 0) break
                                    fos.write(buf, 0, n)
                                    remaining -= n
                                }
                            }
                            fileModes.add(out to entry.mode)
                        }

                        else -> Unit // 设备节点 / FIFO 在 PRoot 里无意义，跳过
                    }

                    val consumed = tar.bytesRead
                    if (consumed - lastReport >= 4L * 1024 * 1024) {
                        lastReport = consumed
                        onProgress(consumed)
                    }
                }
                onProgress(lastReport)
            }
        }

        // 先给普通文件落权限，再倒序落目录权限（避免只读目录挡住后续写入）
        for ((f, mode) in fileModes) chmod(f, mode)
        for ((f, mode) in dirModes.asReversed()) chmod(f, mode)
    }

    /**
     * 判断 [archive] 是不是本 App 导出的 .zlbak。
     *
     * 只看最前面几个条目里有没有根部 `meta/backup.json`，不解压正文。
     * 用户经常把备份包丢进「从本地导入 rootfs」入口，若直接按 rootfs 解压，
     * 恢复出来的容器根目录里会多一层 `rootfs/`，guest 里连 /bin/sh 都没有。
     */
    fun looksLikeBackup(archive: File): Boolean = runCatching {
        openStream(archive).use { raw ->
            TarArchiveInputStream(BufferedInputStream(raw, 64 * 1024)).use { tar ->
                var seen = 0
                while (seen < 16) {
                    val entry = tar.nextEntry ?: return@use false
                    seen++
                    if (sanitize(entry.name) == "meta/backup.json") return@use true
                }
                false
            }
        }
    }.getOrDefault(false)

    private fun openStream(archive: File): InputStream {
        // 不能靠扩展名判断：备份恢复时 SAF 过来的文件会被改名成 backup.zlbak，
        // 后缀信息就丢了。直接嗅探文件头更稳。
        val base = BufferedInputStream(FileInputStream(archive), 256 * 1024)
        base.mark(6)
        val magic = ByteArray(6)
        val n = runCatching { base.read(magic) }.getOrDefault(-1)
        runCatching { base.reset() }
        val isGzip = n >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()
        val isXz = n >= 6 && magic[0] == 0xFD.toByte() &&
            magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte() &&
            magic[3] == 'X'.code.toByte() && magic[4] == 'Z'.code.toByte()
        return when {
            isGzip -> GzipCompressorInputStream(base, true)
            isXz -> XZCompressorInputStream(base, true)
            else -> base
        }
    }

    /** 防目录穿越：拒绝绝对路径与 .. 段。 */
    private fun sanitize(name: String): String? {
        var n = name.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimStart('/')
        if (n.isEmpty()) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun chmod(file: File, mode: Int) {
        // TarArchiveEntry.mode 含文件类型位，chmod 只接受权限位
        runCatching { Os.chmod(file.absolutePath, mode and 0xFFF) }
    }
}
