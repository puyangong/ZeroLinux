package com.zerolinux.app.files

import android.content.Context
import com.zerolinux.app.core.AppPaths
import org.json.JSONObject
import java.io.File

/**
 * 容器在「外接存储」上的专属目录。
 *
 * MT 管理器只能看到 getExternalFilesDir()，看不到 filesDir/containers/<uuid>，
 * 所以每个容器在外部存储里再开一个以容器 id 命名的目录，由 ProotLauncher
 * bind 成 guest 的 /external；所有容器共用的旧目录仍挂在 /mnt/external-shared，
 * 旧文件不迁移、不删除。
 *
 * 目录名优先用 id（可读、方便在 MT 里认），不合法或冲突时退回 uuid。
 * 目录里写一个 [UUID_MARKER]，这样 renameId 之后下一次解析目录时能把
 * 老目录改名到新 id，而不用去改 ContainerRepo 的落库顺序。
 */
object ContainerExternal {

    /** guest 内的容器专属外接目录。 */
    const val GUEST_PATH = "/external"

    /** guest 内的旧共享外接目录（所有容器共用）。 */
    const val SHARED_GUEST_PATH = "/mnt/external-shared"

    private const val MAX_NAME = 32
    private const val UUID_MARKER = ".zl-container-uuid"

    private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

    /**
     * 目录名：id 合法且不是 `.` / `..` 时用它，否则退回 uuid。
     * 任何情况下都返回单段文件名，不含路径分隔符。
     */
    fun safe(id: String?, uuid: String): String {
        val candidate = id?.trim().orEmpty()
        if (candidate != "." && candidate != ".." && SAFE_ID.matches(candidate)) {
            return candidate
        }
        val fallback = uuid.trim()
            .replace(UNSAFE_CHARS, "_")
            .trim('.')
            .take(MAX_NAME)
        return fallback.ifEmpty { "container" }
    }

    /** `.../Android/data/<pkg>/files/containers`；外部存储不可用时退回内部目录。 */
    fun containersRoot(ctx: Context): File = File(externalRoot(ctx), "containers")

    /** 旧版本所有容器共用的外部目录，也就是 getExternalFilesDir(null) 本身。 */
    fun legacyDir(ctx: Context): File = externalRoot(ctx)

    /**
     * 取当前容器的外接目录并确保存在。
     *
     * 若目标目录还不存在，但同级的某个目录里带着同一 uuid 的标记（改过 id），
     * 先把它改名过来；改名失败就留在原处，绝不删除。
     */
    fun dir(ctx: Context, id: String?, uuid: String): File {
        val root = containersRoot(ctx)
        val target = File(root, safe(id, uuid))
        if (!target.isDirectory) adopt(root, uuid, target)
        if (!target.exists()) target.mkdirs()
        markUuid(target, uuid)
        return target
    }

    /**
     * 删除容器的外接目录。只允许删除 `.../containers/` 下面的子目录：
     * canonicalPath 越界（或就是 containers 本身）时直接放弃，避免 id 被写成
     * `..`、绝对路径之类时误删兄弟目录甚至整个外部目录。
     */
    fun remove(ctx: Context, id: String?, uuid: String) {
        val root = containersRoot(ctx)
        deleteInside(root, File(root, safe(id, uuid)))
    }

    /**
     * id 改名后把外接目录从旧名字搬到新名字：旧目录存在、新目录不存在才动手，
     * 失败静默（旧目录保留，不删除）。
     */
    fun rename(ctx: Context, oldId: String?, newId: String?, uuid: String) {
        val root = containersRoot(ctx)
        val from = File(root, safe(oldId, uuid))
        val to = File(root, safe(newId, uuid))
        if (from.absolutePath == to.absolutePath) return
        if (!from.isDirectory || to.exists()) return
        runCatching { from.renameTo(to) }
            .onSuccess { moved -> if (moved) markUuid(to, uuid) }
    }

    /** 从 container.json 侧车读当前 id；读不到时返回 null（调用方退回 uuid）。 */
    fun resolveId(ctx: Context, uuid: String): String? {
        val text = runCatching {
            AppPaths.containerJson(ctx, uuid).takeIf { it.isFile }?.readText()
        }.getOrNull() ?: return null
        return runCatching { JSONObject(text).optString("id") }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ inner

    private fun externalRoot(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "external")

    /** 找到「带同一 uuid 标记」的旧目录并改名成 target。 */
    private fun adopt(root: File, uuid: String, target: File) {
        if (uuid.isBlank() || target.exists()) return
        val siblings = runCatching { root.listFiles() }.getOrNull() ?: return
        for (dir in siblings) {
            if (!dir.isDirectory) continue
            val owner = runCatching {
                File(dir, UUID_MARKER).takeIf { it.isFile }?.readText()?.trim()
            }.getOrNull()
            if (owner != uuid) continue
            if (runCatching { dir.renameTo(target) }.getOrDefault(false)) return
        }
    }

    /** 只删除 root 的正经子目录；canonicalPath 越界一律不动。 */
    private fun deleteInside(root: File, target: File) {
        if (!target.exists()) return
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return
        val prefix = rootPath.trimEnd(File.separatorChar) + File.separator
        if (targetPath == rootPath || !targetPath.startsWith(prefix)) return
        runCatching { target.deleteRecursively() }
    }

    private fun markUuid(dir: File, uuid: String) {
        if (uuid.isBlank() || !dir.isDirectory) return
        runCatching {
            val marker = File(dir, UUID_MARKER)
            if (!marker.isFile || marker.readText().trim() != uuid) marker.writeText(uuid)
        }
    }
}
