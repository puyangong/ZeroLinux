package com.zerolinux.app.engine

import android.content.Context
import com.zerolinux.app.core.AppPaths
import java.io.File
import java.nio.file.Files

/**
 * 一个可见或隐藏的终端会话，对应一个 proot 子进程。
 *
 * 读循环固定 4KB，读到就交给上层合帧（16ms）后推给 xterm；
 * 写和 resize 直接转发到 PTY。会话互不影响：各自独立 pid / fd。
 */
class TerminalSession(
    val sid: String,
    val containerUuid: String,
    var containerId: String,
    val hidden: Boolean = false,
    var title: String = "shell"
) {

    @Volatile
    private var process: PtyProcess? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var pid: Int = -1
        private set

    @Volatile
    var lastExit: Int = Int.MIN_VALUE
        private set

    /** 由 SessionRegistry 挂上，参数是原始字节。 */
    var onData: ((ByteArray) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null

    private var reader: Thread? = null

    /** 当前 proot 进程的代号；重启会话后旧读循环的 finally 不能把新会话标记成已退出。 */
    @Volatile
    private var generation = 0

    /**
     * 最近输出的原始字节（含颜色/控制序列）。
     *
     * 会话进程在后台一直活着，但 WebView 页面可能被系统回收；页面重建后
     * xterm 是新实例，内存里没有任何历史。这里保留一份有界回放缓冲，
     * 由 JS 在创建终端后主动拉取，避免回来看不到之前的输出。
     */
    private val replayLock = Any()
    private val replayBuf = ByteArray(REPLAY_BYTES)
    private var replayWrite = 0
    private var replaySize = 0

    private companion object {
        /** 回放缓冲上限：够覆盖一屏历史命令，又不至于占太多内存。 */
        const val REPLAY_BYTES = 256 * 1024
    }

    fun start(ctx: Context, cols: Int, rows: Int): Boolean {
        if (running) return true
        val rootfs = AppPaths.containerRootfs(ctx, containerUuid)
        if (!rootfs.isDirectory) return false
        ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)

        val gen = synchronized(this) {
            generation += 1
            generation
        }
        // 新 shell 从空屏开始，旧的输出不能再回放。
        clearReplay()
        // 不能固定 /bin/sh：Debian/Ubuntu/Kali 的 /bin/sh 是 dash，没有行编辑，
        // 方向键只会回显 ^[[A。改由 resolveLoginShell 选可用的登录 shell。
        val shell = resolveLoginShell(rootfs)
        android.util.Log.i("ZlSession", "start sid=$sid shell=${shell.first()}")
        val proc = ProotLauncher.start(
            ctx = ctx,
            rootfs = rootfs,
            command = shell,
            workDir = "/root",
            cols = cols.coerceAtLeast(20),
            rows = rows.coerceAtLeast(5),
            // SHELL 跟随真正启动的 bash：部分 /etc/profile 和子进程会读它。
            extraEnv = mapOf("SHELL" to shell.first(), "TERM" to "xterm-256color")
        ) ?: return false

        process = proc
        pid = proc.pid
        running = true
        // 日志目录在会话第一次启动时建好，旧 last.txt 会被归档。
        TerminalLogStore.start(ctx, this)
        reader = Thread({ readLoop(proc, gen) }, "zl-pty-$sid").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(proc: PtyProcess, gen: Int) {
        val buf = ByteArray(4096)
        // 新 shell 的第一段输出可能在提示符前带一个空格（部分 rootfs 的 profile
        // 或 PTY 初始化会这样）。只丢弃「整段输出最开头」的 ASCII 空格，
        // 不碰命令输出里的缩进。
        var atStart = true
        try {
            while (true) {
                val n = proc.inputStream.read(buf)
                if (n <= 0) break
                // 旧进程的尾部输出不能写进重启后的新终端，否则会出现前导空格/旧提示符。
                if (gen != generation) return
                var start = 0
                if (atStart) {
                    while (start < n && buf[start] == ' '.code.toByte()) start++
                    if (start < n) atStart = false
                }
                if (start >= n) continue
                val chunk = ByteArray(n - start)
                System.arraycopy(buf, start, chunk, 0, chunk.size)
                // 先落日志再推给 WebView：隐藏会话/页面重载期间也不会漏字节。
                TerminalLogStore.append(this, chunk)
                // 同时记一份原始字节，供页面重建后回放。
                remember(chunk)
                onData?.invoke(chunk)
            }
        } catch (_: Throwable) {
            // 进程被杀或 fd 关闭都会走到这里
        } finally {
            if (gen == generation) {
                running = false
                val code = runCatching { PtyNative.waitForProcess(proc.pid) }.getOrDefault(-1)
                lastExit = code
                android.util.Log.i("ZlSession", "exit sid=$sid pid=${proc.pid} code=$code")
                TerminalLogStore.finish(this)
                onExit?.invoke(code)
            }
        }
    }

    /** JS 传来的键盘输入（UTF-8 文本）。 */
    fun write(text: String) {
        val proc = process ?: return
        if (!running) return
        // 先记输入：last-mini 需要把「最后一条命令」和之后的输出配对。
        TerminalLogStore.input(this, text)
        runCatching {
            proc.outputStream.write(text.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        runCatching { process?.updateWindowSize(cols, rows) }
    }

    /**
     * 追加到回放缓冲；超出容量时覆盖最旧的字节。
     *
     * chunk 读取线程独占，但 [replay] 会从 JS 线程来取，所以整体加锁。
     */
    private fun remember(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(replayLock) {
            if (chunk.size >= REPLAY_BYTES) {
                // 单块就超过容量：只留最后 REPLAY_BYTES 字节。
                chunk.copyInto(replayBuf, 0, chunk.size - REPLAY_BYTES, chunk.size)
                replaySize = REPLAY_BYTES
                replayWrite = 0
                return
            }
            var offset = 0
            while (offset < chunk.size) {
                val n = minOf(chunk.size - offset, REPLAY_BYTES - replayWrite)
                chunk.copyInto(replayBuf, replayWrite, offset, offset + n)
                replayWrite = (replayWrite + n) % REPLAY_BYTES
                replaySize = minOf(REPLAY_BYTES, replaySize + n)
                offset += n
            }
        }
    }

    /** 新 shell 启动时清空回放，避免把上一个进程的输出带到新会话。 */
    private fun clearReplay() {
        synchronized(replayLock) {
            replaySize = 0
            replayWrite = 0
        }
    }

    /**
     * 最近输出的副本，供 WebView 页面重建后回放。
     *
     * 缓冲绕圈后开头可能落在半条 ANSI 转义序列中间，此时跳过第一行残片，
     * 避免新终端首行出现乱码。
     */
    fun replay(): ByteArray {
        synchronized(replayLock) {
            if (replaySize <= 0) return ByteArray(0)
            val out = ByteArray(replaySize)
            val start = (replayWrite - replaySize + REPLAY_BYTES) % REPLAY_BYTES
            val first = minOf(replaySize, REPLAY_BYTES - start)
            replayBuf.copyInto(out, 0, start, start + first)
            if (first < replaySize) replayBuf.copyInto(out, first, 0, replaySize - first)
            if (replaySize == REPLAY_BYTES) {
                var cut = -1
                val limit = minOf(out.size, 4096)
                for (i in 0 until limit) {
                    if (out[i] == '\n'.code.toByte()) {
                        cut = i + 1
                        break
                    }
                }
                if (cut > 0 && cut < out.size) return out.copyOfRange(cut, out.size)
            }
            return out
        }
    }

    fun kill() {
        synchronized(this) { generation += 1 }
        running = false
        // 进程销毁前先请求落盘；读循环的 finally 还会再 finish 一次。
        TerminalLogStore.flush(this)
        runCatching { process?.destroy() }
        process = null
    }
}

/** bash 在 guest 里的常见位置（usr-merge 两种情况都覆盖）。 */
private val BASH_GUEST_PATHS = listOf("/bin/bash", "/usr/bin/bash")

/** 明确不能当交互登录 shell 的占位值。 */
private val NON_LOGIN_SHELLS = setOf(
    "/usr/sbin/nologin", "/sbin/nologin", "/usr/bin/nologin", "/bin/nologin",
    "/bin/false", "/usr/bin/false", "nologin", "false"
)

/**
 * 选出 guest 的交互登录 shell，返回可执行命令（`[shell, "-l", "-i"]`）。
 *
 * 不能固定 `["/bin/sh", "-l"]`：Debian/Ubuntu/Kali 的 /bin/sh 是 dash，
 * dash 没有 readline 行编辑，方向键发出的 CSI 序列（`^[[A`）不会被消费，
 * 只会被原样回显到终端。这些镜像的 /etc/passwd 里登录 shell 是 /bin/bash，
 * 所以优先用 bash；Alpine 没有 bash 时用自带行编辑的 busybox ash。
 *
 * `-i` 必须显式传：个别 PRoot/PTY 组合下 bash 会把会话误判成非交互，
 * readline 关闭后方向键又会被行规程回显成 `^[[A`。显式 -i 保证 readline 开启。
 */
internal fun resolveLoginShell(rootfs: File, user: String = "root"): List<String> {
    val bash = BASH_GUEST_PATHS.firstOrNull { guestFileExists(rootfs, it) }
    val fromPasswd = passwdLoginShell(rootfs, user)?.takeIf { guestFileExists(rootfs, it) }
    // passwd 里写 /bin/sh 或 dash 时仍然优先 bash：dash 没有行编辑。
    val shell = when {
        bash != null && (fromPasswd == null || fromPasswd == "/bin/sh" || fromPasswd.endsWith("/dash")) -> bash
        fromPasswd != null -> fromPasswd
        bash != null -> bash
        else -> "/bin/sh"
    }
    return listOf(shell, "-l", "-i")
}

/** 读 guest 的 /etc/passwd，返回登录用户的第 7 字段（未校验存在性）。 */
private fun passwdLoginShell(rootfs: File, user: String): String? {
    val passwd = File(rootfs, "etc/passwd")
    if (!passwd.isFile) return null
    val entries = runCatching { passwd.readLines() }.getOrNull() ?: return null
    val parsed = entries.mapNotNull { line ->
        val fields = line.trim().split(':')
        // 跳过注释/NIS 行与格式不全的行。
        if (fields.size < 7 || line.startsWith("#") || line.startsWith("+")) null else fields
    }
    val matched = parsed.firstOrNull { it[0] == user }
        ?: parsed.firstOrNull { it[2] == "0" } // 用户名对不上时按 uid 0 认 root
        ?: if (user == "root") parsed.firstOrNull() else null
    val shell = matched?.get(6)?.trim().orEmpty()
    if (shell.isEmpty() || !shell.startsWith("/")) return null
    if (shell in NON_LOGIN_SHELLS) return null
    return shell
}

/**
 * guest 绝对路径在 rootfs 内是否可用。
 *
 * rootfs 的 /bin 可能是真实目录，也可能是指向 usr/bin 的符号链接；后者的
 * 目标常写成 guest 绝对路径（/usr/bin），在宿主侧解析会跑到 Android 的
 * /usr/bin。所以 /bin/x 与 /usr/bin/x 两侧都按宿主路径查，并接受指向
 * guest 的符号链接本身存在（如 Alpine 的 /bin/sh -> /bin/busybox）。
 */
private fun guestFileExists(rootfs: File, guestPath: String): Boolean {
    val cleaned = guestPath.trim()
    if (cleaned.isEmpty() || !cleaned.startsWith("/")) return false
    return guestPathCandidates(cleaned.trimStart('/')).any { rel ->
        val file = File(rootfs, rel)
        file.isFile ||
            (!file.isDirectory && file.exists()) ||
            runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
    }
}

/** 补上 usr-merge 的另一侧路径。 */
private fun guestPathCandidates(rel: String): List<String> = when {
    rel.startsWith("bin/") -> listOf(rel, "usr/$rel")
    rel.startsWith("usr/bin/") -> listOf(rel, rel.removePrefix("usr/"))
    rel.startsWith("sbin/") -> listOf(rel, "usr/$rel")
    rel.startsWith("usr/sbin/") -> listOf(rel, rel.removePrefix("usr/"))
    else -> listOf(rel)
}
