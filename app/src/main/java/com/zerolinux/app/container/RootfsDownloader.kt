package com.zerolinux.app.container

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * 断线重下 + SHA256 校验。缓存文件放在 cacheDir，成功后清理。
 *
 * 支持多候选源：每个候选内部先重连 3 次，失败后切换下一个镜像；
 * 每次重连删除当前分片、从 0 重下，不读取旧文件大小。
 * 全部候选都失败才抛错（错误信息带上试过的域名）。
 */
class RootfsDownloader {

    data class Outcome(val file: File, val sha256: String, val verified: Boolean)

    /** 单源下载（例如 CLI tarball 这类只有官方地址的资源）。 */
    suspend fun download(
        source: RootfsSource,
        destDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Outcome = downloadAll(listOf(source), destDir, onProgress)

    /** 多候选下载：按 [sources] 顺序尝试，成功一个即返回。 */
    suspend fun downloadAll(
        sources: List<RootfsSource>,
        destDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        // 每个镜像内部先重连 3 次；3 次都失败才换下一个镜像。
        // 所有镜像轮完后等待一会儿再从头开始，形成“周而复始”的重试环。
        repeat(MAX_CYCLES) { cycle ->
            try {
                return@withContext downloadOnce(sources, destDir, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                Log.w(TAG, "download cycle ${cycle + 1}/$MAX_CYCLES failed: ${e.message}")
                if (cycle < MAX_CYCLES - 1) {
                    delay(2_000L * (cycle + 1).coerceAtMost(6))
                }
            }
        }
        throw last ?: IllegalStateException("所有镜像均下载失败")
    }

    private suspend fun downloadOnce(
        sources: List<RootfsSource>,
        destDir: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "no rootfs source" }
        destDir.mkdirs()
        // 不再并行探活排序：探活在部分镜像/大文件上会卡住（实测 Kali 的 Cloudflare
        // Range 探测长时间不返回），导致下载器还没开始下载就停在 0%。
        // 候选顺序本身已经按实测可用性排好，直接按顺序尝试；失败会快速进入下一个。
        val ordered = sources
        val fileName = ordered.first().fileName
        val target = File(destDir, fileName)
        val part = File(destDir, "$fileName.part")

        if (target.exists() && target.length() > 0) {
            for (source in ordered) {
                val check = verifyChecksum(target, source, ordered) ?: continue
                return@withContext Outcome(target, check.sha256, check.verified)
            }
            target.delete()
        }

        val tried = LinkedHashSet<String>()
        var last: Throwable? = null

        Log.i(
            TAG,
            "download start: ${ordered.joinToString { hostOf(it.url) }} " +
                "dest=${destDir.absolutePath} part=${part.length()} target=${target.length()}"
        )

        for (source in ordered) {
            tried += hostOf(source.url)
            try {
                fetch(source, part, onProgress)
                if (!part.exists() || part.length() == 0L) {
                    throw IllegalStateException("下载失败：文件为空")
                }
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("无法覆盖 ${target.name}")
                }
                if (!part.renameTo(target)) throw IllegalStateException("无法写入 ${target.name}")

                val check = verifyChecksum(target, source, ordered)
                if (check == null) {
                    // 校验不通过说明这份 part 不可信，换镜像时从头再来。
                    target.delete()
                    part.delete()
                    throw IllegalStateException("SHA256 校验失败")
                }
                return@withContext Outcome(target, check.sha256, check.verified)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "mirror failed: ${hostOf(source.url)} ${e.message}")
            }
        }

        val hosts = tried.joinToString(", ").ifBlank { "unknown" }
        throw IllegalStateException(
            "所有镜像均下载失败（已尝试 $hosts）：${last?.message ?: "unknown"}",
            last
        )
    }

    /**
     * 单候选：先重连 3 次；3 次都失败才让调用方切换镜像。
     * 每次重连都删除当前分片、从 0 重新下载这个文件；已完成的其他镜像/包不受影响。
     */
    private suspend fun fetch(
        source: RootfsSource,
        part: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val srcMark = File(part.parentFile, part.name + ".src")
        val host = runCatching { java.net.URI(source.url).host }.getOrNull().orEmpty()
        val prevHost = runCatching { srcMark.readText() }.getOrNull()
        if (prevHost != host) Log.i(TAG, "switch mirror $prevHost -> $host, restart from 0")
        runCatching { srcMark.writeText(host) }

        var lastError: Exception? = null
        for (attempt in 1..3) {
            // 断线重连不做 Range 续传：删除这个文件的分片，从头下载当前镜像。
            if (part.exists()) part.delete()
            try {
                fetchOnce(source, part, onProgress)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "mirror $host attempt=$attempt/3 failed: ${e.message}")
                if (attempt < 3) delay(500L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("下载失败：${source.url}")
    }

    /** 单次连接：当前文件从 0 开始下载，写满后返回。 */
    private suspend fun fetchOnce(
        source: RootfsSource,
        part: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var lastHttpCode = -1
        var call: Call? = null
        try {
            val offset = 0L
            Log.i(TAG, "connect ${hostOf(source.url)} offset=$offset")
            val request = Request.Builder()
                .url(source.url)
                .header("User-Agent", Http.UA)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()
            val current = Http.httpClient.newCall(request)
            call = current
            // 协程被取消（删除容器）时立刻断开连接，避免下载线程继续占着槽位。
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { current.cancel() }
            try {
                current.execute().use { response ->
                    val code = response.code
                    lastHttpCode = code
                    val body = response.body
                    val length = body?.contentLength() ?: -1L
                    Log.i(
                        TAG,
                        "connected ${hostOf(source.url)} code=$code len=$length " +
                            "type=${response.header("Content-Type")} offset=$offset"
                    )
                    if (code == 416) {
                        // 服务器认为范围已满足：part 可能已完整。
                        return
                    }
                    if (code !in 200..299) throw IllegalStateException("HTTP $code (${source.url})")
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    if (contentType.startsWith("text/html")) {
                        throw IOException("HTTP $code content-type text/html（镜像返回错误页）(${source.url})")
                    }
                    val remaining = length.coerceAtLeast(0L)
                    val total = remaining
                    val input = body?.byteStream() ?: throw IOException("empty body (${source.url})")

                    RandomAccessFile(part, "rw").use { raf ->
                        raf.setLength(0)
                        input.use { stream ->
                            val buf = ByteArray(256 * 1024)
                            var since = 0L
                            var written = 0L
                            var loggedFirst = false
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = stream.read(buf)
                                if (n <= 0) break
                                raf.write(buf, 0, n)
                                written += n
                                since += n
                                if (!loggedFirst) {
                                    loggedFirst = true
                                    Log.i(TAG, "first bytes ${hostOf(source.url)} written=$written total=$total")
                                }
                                if (since >= 512 * 1024) {
                                    since = 0
                                    onProgress(written, total)
                                }
                            }
                            onProgress(written, total)
                        }
                    }
                }
                return
            } finally {
                cancelHandle?.dispose()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            val prefix = if (lastHttpCode > 0) "HTTP $lastHttpCode " else ""
            throw IOException(
                "$prefix${e.message ?: e.javaClass.simpleName} (${source.url})",
                e
            )
        } finally {
            call?.cancel()
        }
    }

    private data class Check(val sha256: String, val verified: Boolean)

    /**
     * 校验结果：
     * - 返回 null 表示「索引里有该条目，但哈希对不上」→ 必须失败；
     * - verified=false 表示无法校验（无索引、索引拉取失败、索引里没有该条目）→ 放行但记日志；
     * - verified=true 表示条目存在且哈希一致。
     */
    private fun verifyChecksum(
        file: File,
        source: RootfsSource,
        allSources: List<RootfsSource> = listOf(source)
    ): Check? {
        val actual = Http.sha256(file)
        val entry = source.checksumEntry.ifBlank { source.fileName }
        val sumUrls = (listOf(source.sumUrl) + allSources.map { it.sumUrl })
            .filter { it.isNotBlank() }
            .distinct()
        if (sumUrls.isEmpty()) return Check(actual, false)

        // 某个镜像的 SHA256SUMS 可能单独挂掉；同文件在其他镜像的索引可互补。
        var sawEntry = false
        for (sumUrl in sumUrls) {
            val sums = Http.getText(sumUrl) ?: continue
            val expected = findChecksumEntry(sums, entry) ?: continue
            sawEntry = true
            if (expected.equals(actual, ignoreCase = true)) return Check(actual, true)
        }
        if (!sawEntry) {
            // 网络镜像偶尔缺条目（例如 LXC 只给 rootfs.tar.xz），不应阻断安装。
            Log.w(TAG, "checksum entry '$entry' not found in $sumUrls, accept as unverified")
            return Check(actual, false)
        }
        return null
    }

    /**
     * 在 SHA256SUMS 里精确匹配条目名。
     * 行格式：`<64 位十六进制哈希>  <文件名>`（GNU）或 `<哈希> *<文件名>`（二进制标记）。
     */
    private fun findChecksumEntry(sumsText: String, entry: String): String? {
        val wanted = entry.trim().removePrefix("./")
        if (wanted.isEmpty()) return null
        for (raw in sumsText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOfFirst { it == ' ' || it == '\t' }
            if (sep <= 0) continue
            val hash = line.substring(0, sep)
            if (hash.length != 64 || hash.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) continue
            val name = line.substring(sep).trim().removePrefix("*").removePrefix("./")
            if (name == wanted) return hash
        }
        return null
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    private companion object {
        const val TAG = "RootfsDownloader"
        const val MAX_CYCLES = 8
    }
}
