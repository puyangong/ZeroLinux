package com.zerolinux.app.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import com.zerolinux.app.container.ContainerRepo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 终端输出的本地留档。
 *
 * 目录：/sdcard/zerolinux/terminal/<容器 label>/<会话 title>/
 *   - last.txt                本次运行的全部输出，追加写；输出停止 1.2s 后落盘
 *   - yyyyMMdd-HHmmss.txt     历史运行，最多 14 个（加上 last.txt 共 15 个）
 *
 * 只存剥掉 ANSI 控制序列的纯文本。所有磁盘写入都在单线程执行器上串行，
 * 不阻塞 PTY 读循环。last.txt 只追加新增部分，容器再大也不会每个输出突发
 * 都整份重写几 MB 日志；只有缓冲区截断/文件被外部改动时才整份重写一次。
 */
object TerminalLogStore {

    private const val TAG = "ZLTemLog"
    private const val DEBOUNCE_MS = 1_200L
    private const val MINI_DEBOUNCE_MS = 300L
    private const val KEEP_ARCHIVES = 14
    private const val MAX_CHARS = 4_000_000
    private const val MAX_MINI = 256_000

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zl-term-log").apply { isDaemon = true }
    }

    private class Entry(
        val sid: String,
        @Volatile var containerLabel: String,
        @Volatile var title: String,
        @Volatile var dir: File
    ) {
        val ansi = AnsiStripper()
        val buf = StringBuilder()
        var pending: ScheduledFuture<*>? = null
        var dirty = false

        /** last.txt 已写入的字符数 / 文件字节数；只由日志线程读写。 */
        var writtenChars = 0
        var writtenBytes = 0L

        /** 缓冲区被截断后必须整份重写一次，之后继续追加。 */
        var rewrite = false

        // last-mini.txt：只留「最后一次输入 + 它之后的输出」。
        val miniLine = StringBuilder()
        val miniOut = StringBuilder()
        var miniCommand: String? = null
        var miniEsc = 0
        var miniDirty = false
        var miniPending: ScheduledFuture<*>? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 外部日志根目录；应用已持有 MANAGE_EXTERNAL_STORAGE。 */
    fun root(): File = File(Environment.getExternalStorageDirectory(), "zerolinux/terminal")

    /** 某个容器在文件抽屉里看到的目录：<root>/<容器 label>。 */
    fun containerDir(label: String): File = File(root(), safeName(label))

    fun dirOf(containerLabel: String, title: String): File =
        File(containerDir(containerLabel), safeName(title))

    /** 会话启动时调用：建目录，并把上一次的 last.txt 归档成时间戳文件。 */
    fun start(ctx: Context, session: TerminalSession) {
        val cfg = runCatching { ContainerRepo(ctx).find(session.containerUuid) }.getOrNull()
        val label = cfg?.label?.takeIf { it.isNotBlank() } ?: session.containerId
        val dir = dirOf(label, session.title)
        val entry = Entry(session.sid, label, session.title, dir)
        entries[session.sid] = entry
        io.execute {
            runCatching {
                dir.mkdirs()
                archiveLast(dir)
            }.onFailure { Log.w(TAG, "start $dir: ${it.message}") }
        }
    }

    /** PTY 读到的每一块原始字节；必须在 onData 之前调用，WebView 挂了也不丢。 */
    fun append(session: TerminalSession, bytes: ByteArray) {
        val e = entries[session.sid] ?: return
        val text = e.ansi.feed(bytes)
        if (text.isEmpty()) return
        synchronized(e.buf) {
            e.buf.append(text)
            if (e.buf.length > MAX_CHARS) {
                e.buf.delete(0, e.buf.length - MAX_CHARS * 3 / 4)
                e.buf.insert(0, "[... 日志过长，前文已截断 ...]\n")
                // 文件已经和内存对不上，下一次落盘必须整份重写。
                e.rewrite = true
                e.writtenChars = 0
                e.writtenBytes = 0L
            }
            e.dirty = true
        }
        synchronized(e) {
            if (e.miniCommand != null) {
                e.miniOut.append(text)
                if (e.miniOut.length > MAX_MINI) e.miniOut.setLength(MAX_MINI)
                e.miniDirty = true
            }
        }
        schedule(e)
        scheduleMini(e)
    }

    /**
     * 键盘输入。只维护 last-mini 的「最后一条命令 + 命令之后的输出」，
     * 不参与 last.txt；ESC/CSI 序列直接跳过，退格按 UTF-16 字符回退。
     */
    fun input(session: TerminalSession, text: String) {
        val e = entries[session.sid] ?: return
        if (text.isEmpty()) return
        var touched = false
        synchronized(e) {
            for (ch in text) {
                if (e.miniEsc == 2) {
                    if (ch.code in 0x40..0x7E) e.miniEsc = 0
                    continue
                }
                if (e.miniEsc == 1) {
                    e.miniEsc = if (ch == '[') 2 else 0
                    continue
                }
                when (ch) {
                    '\u001B' -> e.miniEsc = 1
                    '\r', '\n' -> {
                        val cmd = e.miniLine.toString().trim()
                        e.miniLine.setLength(0)
                        if (cmd.isNotEmpty()) {
                            e.miniCommand = cmd
                            e.miniOut.setLength(0)
                            touched = true
                        }
                    }

                    '\u007F', '\b' -> {
                        if (e.miniLine.isNotEmpty()) {
                            e.miniLine.setLength(e.miniLine.length - 1)
                            touched = true
                        }
                    }

                    '\u0003' -> {
                        e.miniLine.setLength(0)
                        touched = true
                    }

                    else -> if (ch.code >= 0x20) {
                        e.miniLine.append(ch)
                        touched = true
                    }
                }
            }
            if (touched) e.miniDirty = true
        }
        if (touched) scheduleMini(e)
    }

    /** 会话改名：把整个日志目录搬到新名字下（已有文件合并过去）。 */
    fun rename(session: TerminalSession, newTitle: String) {
        val e = entries[session.sid] ?: return
        if (e.title == newTitle) return
        val old = e.dir
        val next = dirOf(e.containerLabel, newTitle)
        e.title = newTitle
        // 只改标题，目录在 IO 线程搬完后再切，避免 flush 写进新目录后又被旧文件覆盖。
        io.execute {
            runCatching {
                flushNow(e)
                if (old != next) {
                    next.mkdirs()
                    old.listFiles()?.forEach { f -> moveInto(f, next) }
                    old.delete()
                    e.dir = next
                }
            }.onFailure { Log.w(TAG, "rename $old -> $next: ${it.message}") }
        }
    }

    /** 立即请求一次落盘（进程/会话即将结束时用），不销毁记录。 */
    fun flush(session: TerminalSession) {
        val e = entries[session.sid] ?: return
        e.pending?.cancel(false)
        e.pending = null
        e.miniPending?.cancel(false)
        e.miniPending = null
        io.execute { runCatching { flushNow(e) } }
    }

    /** 会话结束：刷最后一次并移除内存记录。 */
    fun finish(session: TerminalSession) {
        val e = entries.remove(session.sid) ?: return
        e.pending?.cancel(false)
        e.pending = null
        e.miniPending?.cancel(false)
        e.miniPending = null
        io.execute { runCatching { flushNow(e) } }
    }

    fun flushAll() {
        entries.values.forEach { e ->
            e.pending?.cancel(false)
            e.pending = null
            e.miniPending?.cancel(false)
            e.miniPending = null
            io.execute { runCatching { flushNow(e) } }
        }
    }

    private fun schedule(e: Entry) {
        e.pending?.cancel(false)
        e.pending = io.schedule({ runCatching { flushNow(e) } }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun scheduleMini(e: Entry) {
        e.miniPending?.cancel(false)
        e.miniPending = io.schedule({ runCatching { flushMini(e) } }, MINI_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 落盘：last.txt 追加新增内容，last-mini.txt 整份刷新。
     *
     * 容器很大时 last.txt 可达几 MB；旧实现每次输出突发都 tmp+rename 整份
     * 重写，在 FUSE/sdcardfs 上会卡住 UI 和输入。现在只追加 delta，只有
     * 缓冲区被截断或文件被外部改动时才整份重写。
     */
    private fun flushNow(e: Entry) {
        flushLast(e)
        flushMini(e)
    }

    private fun flushLast(e: Entry) {
        val dir = e.dir
        val last = File(dir, "last.txt")
        // 文件长度检查放在锁外，避免读循环等 IO。
        val fileMatches = last.isFile && last.length() == e.writtenBytes
        var full: String? = null
        var delta: String? = null
        synchronized(e.buf) {
            if (!e.dirty) return
            e.dirty = false
            val needFull = e.rewrite || !fileMatches || e.buf.length < e.writtenChars
            if (needFull) {
                e.rewrite = false
                e.writtenChars = e.buf.length
                full = e.buf.toString()
            } else if (e.buf.length > e.writtenChars) {
                delta = e.buf.substring(e.writtenChars)
                e.writtenChars = e.buf.length
            }
        }
        dir.mkdirs()
        val text = full
        if (text != null) {
            val tmp = File(dir, ".last.txt.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(last)) {
                last.delete()
                if (!tmp.renameTo(last)) {
                    last.writeText(text)
                    tmp.delete()
                }
            }
        } else {
            val chunk = delta ?: return
            FileOutputStream(last, true).use { it.write(chunk.toByteArray(Charsets.UTF_8)) }
        }
        e.writtenBytes = last.length()
    }

    /** last-mini.txt：只留最后一条命令和它之后的输出。 */
    private fun flushMini(e: Entry) {
        val text = synchronized(e) {
            if (!e.miniDirty) return
            e.miniDirty = false
            val cmd = e.miniCommand
            if (cmd == null) {
                val line = e.miniLine.toString().trim()
                if (line.isEmpty()) "" else "$line\n"
            } else {
                buildString {
                    append(cmd)
                    append('\n')
                    append(e.miniOut.toString().trimStart('\n'))
                }
            }
        }
        if (text.isEmpty()) return
        val out = if (text.endsWith('\n')) text else text + "\n"
        val dir = e.dir
        dir.mkdirs()
        val mini = File(dir, "last-mini.txt")
        val tmp = File(dir, ".last-mini.txt.tmp")
        tmp.writeText(out)
        if (!tmp.renameTo(mini)) {
            mini.delete()
            if (!tmp.renameTo(mini)) {
                mini.writeText(out)
                tmp.delete()
            }
        }
    }

    private fun moveInto(file: File, destDir: File) {
        val dest = File(destDir, file.name)
        if (!dest.exists()) {
            if (!file.renameTo(dest)) runCatching { file.copyTo(dest, true); file.delete() }
            return
        }
        if (file.name == "last-mini.txt") {
            // 迷你日志始终以搬过来的这一份为准，不归档。
            runCatching { file.copyTo(dest, true); file.delete() }
            return
        }
        if (file.name == "last.txt") {
            // 目标目录里已有上一次的 last.txt：先把它归档，再让新的覆盖。
            val archive = uniqueArchive(destDir)
            runCatching { dest.renameTo(archive) }
            if (!file.renameTo(dest)) runCatching { file.copyTo(dest, true); file.delete() }
            trimArchives(destDir)
        } else {
            // 同名历史文件以目标目录里的为准。
            file.delete()
        }
    }

    private fun archiveLast(dir: File) {
        val last = File(dir, "last.txt")
        if (!last.isFile || last.length() == 0L) {
            last.delete()
            return
        }
        val dest = uniqueArchive(dir)
        if (!last.renameTo(dest)) {
            runCatching {
                last.copyTo(dest, overwrite = true)
                last.delete()
            }
        }
        trimArchives(dir)
    }

    private fun uniqueArchive(dir: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var dest = File(dir, "$stamp.txt")
        var n = 2
        while (dest.exists()) {
            dest = File(dir, "$stamp-$n.txt")
            n++
        }
        return dest
    }

    /** 按文件名倒序保留最近 14 个时间戳日志。 */
    private fun trimArchives(dir: File) {
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".txt") && f.name != "last.txt" && f.name != "last-mini.txt"
        }
            ?.sortedByDescending { it.name } ?: return
        files.drop(KEEP_ARCHIVES).forEach { runCatching { it.delete() } }
    }

    /** 容器 label / 会话 title 里不能出现在 FAT 文件名中的字符统一换掉。 */
    fun safeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
        return cleaned.ifBlank { "shell" }.take(80)
    }
}

/**
 * 增量剥离 ANSI 转义序列。
 *
 * 输出按 4KB 分块进来，转义序列可能横跨两块，所以状态必须保留。只保留
 * 可读字符与换行/tab；`\r` 单独出现（进度条覆盖）直接丢弃，`\r\n` 归一成 `\n`。
 */
internal class AnsiStripper {
    private var state = 0 // 0=normal 1=esc 2=csi 3=osc 4=osc_esc
    private var pendingCr = false

    fun feed(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (pendingCr) {
                pendingCr = false
                if (ch == '\n') {
                    out.append('\n')
                    continue
                }
                // 单独的 \r：进度条回写，丢弃。
            }
            when (state) {
                1 -> {
                    state = when (ch) {
                        '[' -> 2
                        ']' -> 3
                        else -> 0
                    }
                }

                2 -> if (ch.code in 0x40..0x7E) state = 0
                3 -> when (ch) {
                    '\u0007' -> state = 0
                    '\u001B' -> state = 4
                }

                4 -> state = if (ch == '\\') 0 else 3
                else -> when {
                    ch == '\u001B' -> state = 1
                    ch == '\r' -> pendingCr = true
                    ch == '\n' || ch == '\t' -> out.append(ch)
                    ch.code < 0x20 || ch.code == 0x7F -> Unit
                    else -> out.append(ch)
                }
            }
        }
        return out.toString()
    }
}
