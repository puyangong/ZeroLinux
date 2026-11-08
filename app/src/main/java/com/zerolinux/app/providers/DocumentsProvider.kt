package com.zerolinux.app.providers

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.util.Log
import android.webkit.MimeTypeMap
import com.zerolinux.app.R
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.core.AppPaths
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * 通过 Android 官方 SAF（Storage Access Framework）把**每个容器**的 rootfs
 * 作为独立的外置存储卷暴露给第三方文件管理器（MT 管理器、Material Files、
 * 系统「文件」、FolderSync 等）。
 *
 * ## 与 Termux 单 root 模型的区别
 * Termux 的 `TermuxDocumentsProvider` 只有一条 root，`documentId` 就是绝对路径
 * （`file.getAbsolutePath()`），因此无法表达「多个卷」也不能把卷的信息交给
 * DocumentsUI 做侧边栏分组。这里改成**多 root + 编码后的 documentId**：
 *
 * ```
 * rootId          = "container:<uuid>"   |  "sd"
 * documentId      = "<rootId>/<相对路径>"
 *
 * container:6f1c9d2a-a1b2/etc/hosts      -> /data/user/0/<pkg>/files/containers/<uuid>/rootfs/etc/hosts
 * container:6f1c9d2a-a1b2/               -> rootfs 根目录（也接受 "container:<uuid>:/" 这种旧写法）
 * sd:/DCIM                               -> /storage/emulated/0/DCIM
 * sd:/                                   -> /storage/emulated/0
 * ```
 *
 * 好处：
 *   1. DocumentsUI 侧边栏里每个容器一个卷，用户可以只授权「某个容器」给某个 App
 *      （FLAG_SUPPORTS_IS_CHILD 让系统能判断挂载树归属）；
 *   2. documentId 不再泄露本应用私有路径（absolute path 会被明文写在 URI 里）；
 *   3. 增删容器只需改动 [queryRoots]，不需要改动任何持久化的 document id ——
 *      唯一需要注意的是**容器被删除后旧 documentId 会失效**，这是预期行为
 *      （系统会在 notifyChange 后重新拉取 root 列表）。
 *
 * ## 第三方客户端的两个硬要求（历史上「看不到容器」就栽在这里）
 *   1. **必须按调用方给的 projection 构造游标**。很多客户端（MT 管理器、
 *      Material Files、FolderSync）会对 `Root.COLUMN_MIME_TYPES` 之类列做
 *      `getColumnIndexOrThrow`；游标里没有这一列就直接抛
 *      IllegalArgumentException，整个 provider 在它们眼里等于不存在。
 *      以前的实现固定返回 `MatrixCursor(ROOT_COLUMNS)`，而 ROOT_COLUMNS 恰好
 *      缺 MIME_TYPES —— 系统 DocumentsUI 能容忍，第三方客户端不容忍。
 *   2. **queryRoots 绝不能抛异常、也不能因为容器元数据读失败就返回空集合**。
 *      系统/客户端一抛就静默丢弃整个 provider。现在任何异常都至少回一条 sd。
 *
 * ## 安全
 * 每个 documentId 都被绑定到它所属的 root；[resolve] 会：
 *   - 校验 uuid 字形（拒绝 `..`、`/`、空串）；
 *   - 取 `canonicalFile` 展开 `..`、`//`、**符号链接**；
 *   - 要求结果 canonicalPath 等于根目录，或以「根目录 + /」开头，否则
 *     FileNotFoundException。因此 rootfs 里指向 `/data/data` 或 `/system`
 *     的 symlink 也逃不出去（这是 Termux 那版只靠 startsWith 不处理的一点）。
 *
 * 谁可以调用：manifest 里声明 `android:permission="android.permission.MANAGE_DOCUMENTS"`，
 * 该权限是 signature 级，只有系统 DocumentsUI 持有；第三方 App 只能通过系统代理
 * 拿到的 URI 访问，无法直接 binder 调用本 provider（`adb shell content query`
 * 也一样会被拒，报 `SecurityException ... obtain access using ACTION_OPEN_DOCUMENT`，
 * 这属于预期行为，不是 bug）。
 *
 * 进程：manifest 里固定 `:main`，与容器元数据的唯一写者同进程，
 * 免得起第二个进程去读同一批 rootfs 目录。
 */
class DocumentsProvider : android.provider.DocumentsProvider() {

    companion object {
        /** 搜索上限，防止在 rootfs 里递归扫出十万条。 */
        private const val MAX_SEARCH_RESULTS = 200
        private const val MAX_SEARCH_DEPTH = 4

        /** 与 manifest 的 `${applicationId}.documents` 保持一致。 */
        private const val AUTHORITY_SUFFIX = ".documents"

        private const val ROOT_SD = "sd"
        private const val ROOT_CONTAINER_PREFIX = "container:"
        private const val SEP = "/"

        /** logcat 过滤用：`adb logcat -s ZeroLinuxDocs`。 */
        const val TAG = "ZeroLinuxDocs"

        /**
         * 容器 uuid 只允许这些字符。显式挡掉 `..`、`.`、`/`、`\\`，
         * 因为 uuid 会被直接拼进路径。
         */
        private val UUID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        private val ROOT_FLAGS =
            Root.FLAG_LOCAL_ONLY or
                Root.FLAG_SUPPORTS_CREATE or
                Root.FLAG_SUPPORTS_SEARCH or
                Root.FLAG_SUPPORTS_IS_CHILD

        /** 默认 root 游标列。**必须**含 MIME_TYPES，见类注释。 */
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_MIME_TYPES
        )

        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY
        )

        /** 所有 doc 行列名，用来过滤调用方传进来的未知列（未知列照样补 null）。 */
        private val KNOWN_DOC_COLUMNS = DOC_COLUMNS.toHashSet()
    }

    /** 一个可展示的卷。dir 是该卷的物理根目录。 */
    private data class RootRef(
        val rootId: String,
        val title: String,
        val summary: String,
        val dir: File,
        val icon: Int
    )

    /** 已解析的 documentId：归属哪个卷 + 物理文件 + 卷的物理根目录。 */
    private data class DocRef(val rootId: String, val file: File, val rootDir: File)

    // 用于「容器增删后通知 DocumentsUI」——只在集合真的变化时发一次广播。
    private var knownRootIds: Set<String>? = null
    private var lastNotifyAt = 0L

    // ------------------------------------------------------------------- 根目录

    private fun appContext() = context?.applicationContext
        ?: requireNotNull(context) { "provider not attached" }

    @Suppress("DEPRECATION")
    private fun externalRoot(): File = Environment.getExternalStorageDirectory()

    private fun appIcon(): Int =
        runCatching { appContext().applicationInfo.icon }.getOrDefault(0)

    /**
     * 每个容器一行 root。
     * 容器信息来自 [ContainerRepo]（无记录时退化为 uuid 前 8 位），
     * rootfs 不存在的容器不返回，避免文件管理器里出现点不进去的死条目。
     *
     * 这里用 [ContainerRepo.readOnlySnapshot] 而不是 `all()`：后者会拿
     * `containers.lock` 的 FileLock，而 `:terminal` 进程随时可能持有同一把锁，
     * 系统回调 queryRoots 时一旦阻塞，DocumentsUI 就直接把这个 provider 判定
     * 为「无响应」并丢掉全部 root。无锁读加上侧车文件兜底，任何元数据故障
     * 都不会让卷消失。
     */
    private fun containerRoots(): List<RootRef> {
        val ctx = appContext()
        val icon = appIcon()
        val out = ArrayList<RootRef>()
        val seen = HashSet<String>()

        fun add(uuid: String, label: String, distro: String) {
            if (uuid.isBlank() || !UUID_RE.matches(uuid) || !seen.add(uuid)) return
            val dir = runCatching { AppPaths.containerRootfs(ctx, uuid) }.getOrNull() ?: return
            if (!dir.isDirectory) return
            out.add(
                RootRef(
                    rootId = ROOT_CONTAINER_PREFIX + uuid,
                    title = label.ifBlank { uuid.take(8) },
                    summary = if (distro.isBlank()) ctx.getString(R.string.docs_container_summary)
                    else ctx.getString(R.string.docs_container_summary_distro, distro),
                    dir = dir,
                    icon = icon
                )
            )
        }

        val configs = runCatching { ContainerRepo(ctx).readOnlySnapshot().items }
            .onFailure { Log.w(TAG, "container repo read failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(emptyList())

        for (cfg in configs) {
            val uuid = runCatching { cfg.uuid }.getOrNull().orEmpty()
            val label = runCatching { cfg.label }.getOrNull().orEmpty()
            val distro = runCatching { cfg.distro.key }.getOrNull().orEmpty()
            add(uuid, label, distro)
        }

        // 兜底：containers.json 缺失 / 解析失败 / 被锁住时，直接扫目录里的侧车文件。
        if (out.isEmpty()) {
            val scanned = runCatching { sidecarRoots() }.getOrElse {
                Log.w(TAG, "sidecar scan failed: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }
            for ((uuid, label, distro) in scanned) add(uuid, label, distro)
        }
        return out.sortedBy { it.title.lowercase() }
    }

    /** 直接读 `files/containers/<uuid>/container.json`（与 [ContainerRepo] 的 sidecar 镜像同源）。 */
    private fun sidecarRoots(): List<Triple<String, String, String>> {
        val dirs = AppPaths.containersDir(appContext()).listFiles() ?: return emptyList()
        val out = ArrayList<Triple<String, String, String>>(dirs.size)
        for (d in dirs) {
            if (!d.isDirectory) continue
            if (!File(d, "rootfs").isDirectory) continue
            val json = runCatching {
                JSONObject(File(d, "container.json").readText())
            }.getOrNull()
            val uuid = json?.optString("uuid").orEmpty().ifBlank { d.name }
            val label = json?.optString("displayName").orEmpty().ifBlank {
                json?.optString("id").orEmpty()
            }
            val distro = json?.optString("distro").orEmpty()
            out.add(Triple(uuid, label, distro))
        }
        return out
    }

    private fun roots(): List<RootRef> {
        val out = ArrayList<RootRef>()
        out.addAll(containerRoots())
        // 只暴露容器卷：用户明确不需要「用户可见的内部存储」这一卷。
        return out
    }

    /** 极端情况下连 `Environment` 都拿不到时的最后一道防线：只吐 sd。 */
    private fun sdOnlyRoots(): List<RootRef> =
        runCatching { roots().filter { it.rootId == ROOT_SD } }.getOrDefault(
            runCatching {
                listOf(
                    RootRef(
                        ROOT_SD,
                        "Internal storage",
                        appContext().getString(R.string.docs_internal_storage_desc),
                        externalRoot(),
                        0
                    )
                )
            }.getOrDefault(emptyList())
        )

    private fun rootDirFor(rootId: String): File? = when {
        rootId == ROOT_SD -> externalRoot()
        rootId.startsWith(ROOT_CONTAINER_PREFIX) -> {
            val uuid = rootId.substring(ROOT_CONTAINER_PREFIX.length)
            if (!UUID_RE.matches(uuid)) null
            else runCatching { AppPaths.containerRootfs(appContext(), uuid) }.getOrNull()
        }
        else -> null
    }

    private fun rootDocId(rootId: String): String = rootId + SEP

    // ------------------------------------------------------------- id 编解码

    /**
     * 有些客户端会把 documentId 直接塞在 URI 里（`content://auth/document/container:<uuid>%2F`），
     * 系统解析 path segment 时未必解码。这里统一先解码一次，避免把 `%2F` 当成文件名字符。
     */
    private fun decodeId(raw: String): String {
        if (raw.indexOf('%') < 0) return raw
        return runCatching { Uri.decode(raw) }.getOrDefault(raw)
    }

    /**
     * `"<rootId>/<相对路径>"` -> (rootId, relPath)。
     *
     * 兼容三种写法：
     *   `container:<uuid>`            -> (container:<uuid>, "/")
     *   `container:<uuid>/`           -> (container:<uuid>, "/")
     *   `container:<uuid>:/etc/hosts` -> (container:<uuid>, "/etc/hosts")（旧描述里的写法）
     * 无斜杠与带斜杠都能吃，避免文件管理器对根 id 做 `trimEnd('/')` 后失效。
     */
    private fun parseDocId(documentId: String): Pair<String, String> {
        val id = decodeId(documentId).trim()
        if (id.isEmpty()) throw FileNotFoundException("请求的 documentId 非法：$documentId")

        if (id.startsWith(ROOT_CONTAINER_PREFIX)) {
            val body = id.substring(ROOT_CONTAINER_PREFIX.length)
            val slash = body.indexOf('/')
            val uuid = (if (slash < 0) body else body.substring(0, slash)).trimEnd(':')
            if (uuid.isEmpty()) throw FileNotFoundException("请求的 documentId 非法：$documentId")
            val rel = if (slash < 0) SEP else body.substring(slash)
            return (ROOT_CONTAINER_PREFIX + uuid) to rel
        }

        val slash = id.indexOf('/')
        val rootId = (if (slash < 0) id else id.substring(0, slash)).trimEnd(':')
        if (rootId.isEmpty()) throw FileNotFoundException("请求的 documentId 非法：$documentId")
        return rootId to if (slash < 0) SEP else id.substring(slash)
    }

    /** canonical 前缀比较，必须带分隔符，避免 `/rootfs-evil` 被当成 `/rootfs` 的子路径。 */
    private fun isUnder(parent: File, child: File): Boolean {
        val p = runCatching { parent.canonicalPath }.getOrElse { parent.absolutePath }.trimEnd('/')
        val c = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
        return c == p || c.startsWith("$p$SEP")
    }

    /**
     * (rootId, relPath) -> 物理 File，强制落在该卷的根目录之内。
     * 任何越界（`..`、绝对路径注入、指向外部的 symlink）都抛 FileNotFoundException。
     */
    private fun resolve(rootId: String, relPath: String): File {
        val base = rootDirFor(rootId) ?: throw FileNotFoundException("未知卷：$rootId")
        val baseCanonical = runCatching { base.canonicalFile }.getOrNull()
            ?: throw FileNotFoundException("卷不可用：$rootId")
        val rel = relPath.replace('\\', '/').trimStart('/')
        if (rel.split('/').any { it == ".." }) throw FileNotFoundException("非法相对路径：$rel")

        val candidate = if (rel.isEmpty()) baseCanonical else File(baseCanonical, rel)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
            ?: throw FileNotFoundException("documentId 非法：$rootId$SEP$rel")
        if (!isUnder(baseCanonical, canonical)) {
            throw FileNotFoundException("路径逃出卷根：$rootId$SEP$rel")
        }
        return canonical
    }

    private fun docRef(documentId: String): DocRef {
        val (rootId, rel) = parseDocId(documentId)
        val base = rootDirFor(rootId) ?: throw FileNotFoundException("未知卷：$rootId")
        val file = resolve(rootId, rel)
        // SAF 客户端（MT 管理器等）判断「新名字是否已存在」的方式是：用父 documentId
        // 拼出子文档 URI，再 queryDocument。若这里对不存在的路径也返回一行，任何名字
        // 都会被判定为「文件已存在」，MT 的新建/重命名会全部失败，并把卷显示成只读。
        if (!file.exists()) throw FileNotFoundException("文档不存在：$documentId")
        return DocRef(rootId, file, base)
    }

    private fun relativePath(rootDir: File, file: File): String {
        val base = runCatching { rootDir.canonicalPath }.getOrElse { rootDir.absolutePath }
            .trimEnd('/')
        val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        if (path.length <= base.length) return ""
        return path.substring(base.length).trimStart('/')
    }

    private fun docIdOf(ref: DocRef, file: File): String =
        ref.rootId + SEP + relativePath(ref.rootDir, file)

    private fun docIdOf(rootId: String, rootDir: File, file: File): String =
        rootId + SEP + relativePath(rootDir, file)

    // ------------------------------------------------------------------- 生命周期

    override fun onCreate(): Boolean {
        runCatching { AppPaths.ensure(appContext()) }
        Log.i(TAG, "onCreate pid=${android.os.Process.myPid()}")
        return true
    }

    /**
     * 容器被创建 / 删除后，root 集合会变。这里在 queryRoots 时懒比对，
     * 变了才 notifyChange(roots)，DocumentsUI 会自己重拉一次（不会无限循环：
     * 下一次 queryRoots 集合相同就不再发）。
     */
    private fun notifyRootsIfChanged(ids: Set<String>) {
        val previous = knownRootIds
        knownRootIds = ids
        if (previous == null || previous == ids) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 2_000L) return
        lastNotifyAt = now
        runCatching {
            val authority = appContext().packageName + AUTHORITY_SUFFIX
            appContext().contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(authority),
                null
            )
        }
    }

    // ------------------------------------------------------------------- 投影

    /**
     * 按调用方要的列造投影；未知列原样保留（补 null），必需列保证存在。
     * 「必需列」是客户端一定会按名取的那几列，缺了它们就是 crash/丢弃。
     */
    private fun rootProjection(requested: Array<out String>?): Array<String> {
        if (requested.isNullOrEmpty()) return ROOT_COLUMNS
        val out = LinkedHashSet<String>(requested.size + 2)
        for (c in requested) if (c.isNotEmpty()) out.add(c)
        out.add(Root.COLUMN_ROOT_ID)
        out.add(Root.COLUMN_DOCUMENT_ID)
        out.add(Root.COLUMN_MIME_TYPES)
        return out.toTypedArray()
    }

    private fun docProjection(requested: Array<out String>?): Array<String> {
        if (requested.isNullOrEmpty()) return DOC_COLUMNS
        val out = LinkedHashSet<String>(requested.size + 3)
        for (c in requested) if (c.isNotEmpty()) out.add(c)
        out.add(Document.COLUMN_DOCUMENT_ID)
        out.add(Document.COLUMN_DISPLAY_NAME)
        out.add(Document.COLUMN_MIME_TYPE)
        return out.toTypedArray()
    }

    // ------------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        Log.i(TAG, "queryRoots enter projection=" + (projection?.joinToString() ?: "null"))
        val columns = rootProjection(projection)
        val cursor = MatrixCursor(columns)

        // 整段包起来：queryRoots 一抛异常，系统与第三方客户端都会静默丢掉整个
        // provider（表现就是「文件管理器里看不到容器」），所以绝不能抛。
        val list = runCatching { roots() }
            .onFailure { Log.e(TAG, "roots() failed: ${it.javaClass.simpleName}: ${it.message}", it) }
            .getOrElse { sdOnlyRoots() }

        notifyRootsIfChanged(list.map { it.rootId }.toSet())

        var emitted = 0
        for (ref in list) {
            if (!ref.dir.isDirectory) continue
            val values = mapOf(
                Root.COLUMN_ROOT_ID to ref.rootId,
                Root.COLUMN_TITLE to ref.title,
                Root.COLUMN_SUMMARY to ref.summary,
                Root.COLUMN_ICON to ref.icon,
                Root.COLUMN_DOCUMENT_ID to rootDocId(ref.rootId),
                Root.COLUMN_FLAGS to ROOT_FLAGS,
                // 负值会让部分客户端的可用空间显示成乱码/直接跳过这一卷。
                Root.COLUMN_AVAILABLE_BYTES to runCatching { ref.dir.usableSpace }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L),
                Root.COLUMN_MIME_TYPES to "*/*"
            )
            val row = cursor.newRow()
            // 调用方要了本 provider 不认识的列时补 null（而不是抛），否则整个 query 失败。
            for (c in columns) row.add(c, values[c])
            emitted++
        }
        Log.i(TAG, "queryRoots -> $emitted 条: " + list.joinToString { it.rootId })
        return cursor
    }

    // ----------------------------------------------------------------- 文档查询

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = docProjection(projection)
        val ref = docRef(documentId)
        val cursor = MatrixCursor(columns, 1)
        includeFile(cursor, columns, ref.rootId, ref.rootDir, ref.file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = docProjection(projection)
        val parent = docRef(parentDocumentId)
        val cursor = MatrixCursor(columns)
        if (!parent.file.isDirectory) return cursor // 不可读 / 不是目录 -> 空游标，不抛
        val children = parent.file.listFiles()?.toList().orEmpty()
            .sortedWith(
                compareByDescending<File> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
        for (child in children) includeFile(cursor, columns, parent.rootId, parent.rootDir, child)
        Log.i(TAG, "queryChildDocuments($parentDocumentId) -> ${children.size} 项")
        return cursor
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String?,
        projection: Array<out String>?
    ): Cursor {
        val columns = docProjection(projection)
        val cursor = MatrixCursor(columns)
        val base = rootDirFor(rootId) ?: return cursor
        val needle = query?.trim()?.lowercase()
        if (needle.isNullOrEmpty()) return cursor

        var found = 0
        fun walk(dir: File, depth: Int) {
            if (found >= MAX_SEARCH_RESULTS || depth > MAX_SEARCH_DEPTH) return
            val children = dir.listFiles()?.toList().orEmpty() // listFiles() 可能为 null
            for (child in children) {
                if (found >= MAX_SEARCH_RESULTS) return
                if (child.name.lowercase().contains(needle)) {
                    includeFile(cursor, columns, rootId, base, child)
                    found++
                }
                if (child.isDirectory) walk(child, depth + 1)
            }
        }
        runCatching { walk(base, 0) }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(docProjection(projection))

    override fun getDocumentType(documentId: String): String =
        mimeTypeOf(docRef(documentId).file)

    // ------------------------------------------------------------------- 打开

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = docRef(documentId).file
        if (file.isDirectory) throw FileNotFoundException("$documentId 是目录")

        // SAF 的 mode 语义：r / w(截断) / wt(截断) / rw / rwt。
        // 以前写模式失败会静默回退只读，第三方文件管理器以为写入成功，
        // 实际得到 0 字节文件或「保存成功但内容没变」，这里必须把错误抛回去。
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            // rwt 必须带截断：否则第三方用「先读后覆盖写」保存时，旧内容尾巴会残留。
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("不支持的模式：$mode（$documentId）")
        }
        try {
            return ParcelFileDescriptor.open(file, flags)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "openDocument failed id=$documentId mode=$mode flags=$flags", e)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "openDocument failed id=$documentId mode=$mode flags=$flags", e)
            throw FileNotFoundException("打开失败：$documentId（$mode）").apply { initCause(e) }
        }
    }

    // --------------------------------------------------------------- 写操作

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = docRef(parentDocumentId)
        if (!parent.file.isDirectory) {
            throw FileNotFoundException("$parentDocumentId 不是目录")
        }
        val target = uniqueChild(parent.file, sanitize(displayName))
        try {
            val created = if (mimeType == Document.MIME_TYPE_DIR) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.createNewFile()
            }
            if (!created) throw IllegalStateException("创建失败：${target.name}")
            val id = docIdOf(parent, target)
            Log.i(TAG, "createDocument -> $id")
            return id
        } catch (e: Exception) {
            Log.w(TAG, "createDocument failed parent=$parentDocumentId name=${target.name} mime=$mimeType", e)
            throw e
        }
    }

    override fun deleteDocument(documentId: String) {
        val ref = docRef(documentId)
        try {
            // 卷根目录不允许删：删掉就再也进不来了。
            if (isUnder(ref.rootDir, ref.file) && ref.file.canonicalPath == ref.rootDir.canonicalPath) {
                throw IllegalArgumentException("不能删除卷根：$documentId")
            }
            if (!ref.file.delete()) throw FileNotFoundException("删除失败：$documentId")
            Log.i(TAG, "deleteDocument -> $documentId")
        } catch (e: Exception) {
            Log.w(TAG, "deleteDocument failed id=$documentId", e)
            throw e
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val ref = docRef(documentId)
        if (ref.file.canonicalPath == ref.rootDir.canonicalPath) {
            throw IllegalArgumentException("不能重命名卷根：$documentId")
        }
        val parentDir = ref.file.parentFile ?: throw FileNotFoundException(documentId)
        val target = File(parentDir, sanitize(displayName))
        if (target.absolutePath == ref.file.absolutePath) return docIdOf(ref, ref.file)
        if (target.exists()) throw IllegalStateException("目标已存在：${target.name}")
        try {
            // 改名后仍必须落在同一个卷里（sanitize 已经去掉分隔符，这里是兜底）。
            val targetCanonical = runCatching { target.canonicalFile }.getOrNull()
                ?: throw FileNotFoundException("重命名失败：$documentId")
            if (!isUnder(ref.rootDir, targetCanonical)) {
                throw FileNotFoundException("重命名逃出卷根：$documentId")
            }
            if (!ref.file.renameTo(target)) throw FileNotFoundException("重命名失败：$documentId")
            val id = docIdOf(ref, targetCanonical)
            Log.i(TAG, "renameDocument -> $id")
            return id
        } catch (e: Exception) {
            Log.w(TAG, "renameDocument failed id=$documentId name=$displayName", e)
            throw e
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { docRef(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { docRef(documentId) }.getOrNull() ?: return false
        if (parent.rootId != child.rootId) return false
        return isUnder(parent.file, child.file)
    }

    // --------------------------------------------------------------- 小工具

    private fun includeFile(
        cursor: MatrixCursor,
        columns: Array<String>,
        rootId: String,
        rootDir: File,
        file: File
    ) {
        val id = docIdOf(rootId, rootDir, file)
        val isRoot = id == rootId + SEP
        val mime = mimeTypeOf(file)

        // 容器卷内按「父目录可写」上报：rootfs 里大量目录是 Linux 的 555（/bin、/usr），
        // 但用户目录（/root、/home、/tmp）可写；逐项 canWrite 会让第三方文件管理器
        // 把整个卷判成只读。删除/改名取决于父目录，写内容失败时 openDocument 会抛错。
        val inContainer = rootId.startsWith(ROOT_CONTAINER_PREFIX)
        val parentWritable = file.parentFile?.canWrite() == true
        val writable = inContainer || file.canWrite() || parentWritable
        var flags = 0
        if (file.isDirectory) {
            if (writable) {
                // 目录也带 FLAG_SUPPORTS_WRITE：MT 管理器用 DocumentFile.canWrite()
                // 判断挂载模式，只看 root 文档上的 WRITE/DELETE 位；只给
                // FLAG_DIR_SUPPORTS_CREATE 时会被整卷锁成只读。
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE or
                    Document.FLAG_SUPPORTS_WRITE
            }
        } else {
            if (writable) {
                flags = flags or Document.FLAG_SUPPORTS_WRITE or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
            }
            if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        if (!isRoot) {
            if (writable) flags = flags or Document.FLAG_SUPPORTS_RENAME
            flags = flags or Document.FLAG_SUPPORTS_DELETE
        }

        // 卷根的显示名用容器 label：MT 管理器把 root 文档名当挂载名，
        // 用 rootfs 会把内部目录名暴露到挂载点。只改 COLUMN_DISPLAY_NAME，
        // 真实目录（filesDir/containers/<uuid>/rootfs）与备份包内路径都不动。
        val displayName = if (isRoot && rootId.startsWith(ROOT_CONTAINER_PREFIX)) {
            containerRootDisplayName(rootId)
        } else {
            file.name.ifBlank { rootId }
        }

        val values = mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to displayName,
            Document.COLUMN_MIME_TYPE to mime,
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_SIZE to (if (file.isDirectory) 0L else file.length()),
            Document.COLUMN_FLAGS to flags,
            Document.COLUMN_SUMMARY to null
        )
        val row = cursor.newRow()
        for (c in columns) {
            row.add(c, if (KNOWN_DOC_COLUMNS.contains(c)) values[c] else null)
        }
    }

    /**
     * 容器卷根的展示名：按 uuid 查 [ContainerRepo] 的 label，查不到回退 uuid 前 8 位
     * （与 [containerRoots] 的 title 规则一致）。只影响 `COLUMN_DISPLAY_NAME`。
     */
    private fun containerRootDisplayName(rootId: String): String {
        val uuid = rootId.substring(ROOT_CONTAINER_PREFIX.length)
        if (uuid.isBlank()) return rootId
        val label = runCatching {
            ContainerRepo(appContext()).readOnlySnapshot().items
                .firstOrNull { it.uuid == uuid }?.label
        }.getOrNull().orEmpty()
        return label.ifBlank { uuid.take(8) }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        if (ext.isNotEmpty()) {
            runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }
                .getOrNull()
                ?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun sanitize(displayName: String): String {
        val cleaned = displayName
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .trim()
        return cleaned.ifBlank { "untitled" }.take(120)
    }

    /** 同名时追加 ` (1)`、` (2)`，和系统文件管理器行为一致。 */
    private fun uniqueChild(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val stem: String
        val suffix: String
        val dot = name.lastIndexOf('.')
        if (dot > 0 && !name.endsWith(".")) {
            stem = name.substring(0, dot)
            suffix = name.substring(dot)
        } else {
            stem = name
            suffix = ""
        }
        var i = 1
        while (i < 1000) {
            candidate = File(parent, "$stem ($i)$suffix")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(parent, "$stem-${System.currentTimeMillis()}$suffix")
    }
}
