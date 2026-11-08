package com.zerolinux.app.files

import java.io.File
import java.nio.file.Files

/**
 * rootfs 内的文件操作。只做文件管理器用得到的三件小事：
 * 建软链、删、改名。
 *
 * 建软链时目标一律写成 **guest 绝对路径**（`/etc/nginx/nginx.conf`），
 * 因为 PRoot 的 `-r` 会把 guest 绝对路径解析到 rootfs 内；写宿主绝对
 * 路径反而会让容器里看不到。
 */
object FileOps {

    data class Outcome(val ok: Boolean, val error: String = "")

    fun createLink(
        browser: RootfsBrowser,
        sourceGuestPath: String,
        targetDirGuestPath: String,
        linkName: String? = null
    ): Outcome {
        val source = browser.normalize(sourceGuestPath)
        val targetDir = browser.normalize(targetDirGuestPath)
        if (!browser.exists(source)) return Outcome(false, "源不存在")
        val dir = browser.hostOf(targetDir)
        if (!dir.isDirectory) return Outcome(false, "目标目录不存在")

        val name = linkName?.trim()?.takeIf { it.isNotEmpty() } ?: File(source).name
        if (name.isEmpty()) return Outcome(false, "名称无效")
        if (name.contains('/')) return Outcome(false, "名称不能包含 /")

        val link = File(dir, name)
        if (link.exists() || Files.isSymbolicLink(link.toPath())) {
            return Outcome(false, "同名文件已存在")
        }
        return runCatching {
            Files.createSymbolicLink(link.toPath(), File(source).toPath())
            Outcome(true)
        }.getOrElse { Outcome(false, it.message ?: it.javaClass.simpleName) }
    }

    fun delete(browser: RootfsBrowser, guestPath: String): Outcome {
        val p = browser.normalize(guestPath)
        if (p == "/") return Outcome(false, "不能删除根目录")
        val host = browser.hostOf(p)
        if (!host.exists() && !Files.isSymbolicLink(host.toPath())) {
            return Outcome(false, "文件不存在")
        }
        return runCatching {
            // deleteRecursively 对软链只删链接本身，不会跟进目标
            if (!host.deleteRecursively()) error("删除失败")
            Outcome(true)
        }.getOrElse { Outcome(false, it.message ?: it.javaClass.simpleName) }
    }

    fun rename(browser: RootfsBrowser, guestPath: String, newName: String): Outcome {
        val p = browser.normalize(guestPath)
        if (p == "/") return Outcome(false, "不能重命名根目录")
        val name = newName.trim()
        if (name.isEmpty()) return Outcome(false, "名称不能为空")
        if (name.contains('/')) return Outcome(false, "名称不能包含 /")

        val from = browser.hostOf(p)
        if (!from.exists() && !Files.isSymbolicLink(from.toPath())) {
            return Outcome(false, "文件不存在")
        }
        val to = File(from.parentFile, name)
        if (to.exists() || Files.isSymbolicLink(to.toPath())) {
            return Outcome(false, "同名文件已存在")
        }
        return runCatching {
            java.nio.file.Files.move(from.toPath(), to.toPath())
            Outcome(true)
        }.getOrElse { Outcome(false, it.message ?: it.javaClass.simpleName) }
    }
}
