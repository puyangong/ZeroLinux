package com.zerolinux.app.engine

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在容器里跑一次性命令并收集输出。交互式终端在第 3 步复用同一套 PTY 通道。
 */
object ContainerShell {

    data class Result(val exitCode: Int, val output: String)

    /**
     * 脚本收尾哨兵。安装脚本结束时子进程（npm/rustup/go 等）可能还攥着 PTY
     * 从端不放手，master 读不到 EOF，任务就会卡在最后一步一直等。脚本跑完
     * 立刻打这个标记，宿主看到即视为结束，不再等 PTY 关闭。
     */
    const val DONE_MARK = "[ZL:DONE]"

    private const val MAX_OUTPUT = 4 * 1024 * 1024

    suspend fun run(
        ctx: Context,
        rootfs: File,
        command: List<String>,
        timeoutMs: Long = 0L,
        idleTimeoutMs: Long = 0L,
        doneMark: String? = null,
        onLine: ((String) -> Unit)? = null,
        containerId: String? = null,
        containerUuid: String? = null,
        caDir: File? = null
    ): Result = withContext(Dispatchers.IO) {
        val proc = ProotLauncher.start(
            ctx, rootfs, command,
            containerId = containerId,
            containerUuid = containerUuid,
            caDir = caDir
        )
            ?: return@withContext Result(-1, "无法创建 PTY 进程")

        val stalled = AtomicBoolean(false)
        val lastOutputAt = AtomicLong(SystemClock.elapsedRealtime())
        val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) runCatching { proc.destroy() }
        }
        // 看门狗只负责「长时间没有任何输出」：apt 正常配置时也可能安静几分钟，
        // 因此阈值给得比较宽；真正卡死的网络/子进程会在这里被销毁，交给上层重试。
        val watchdog = if (idleTimeoutMs > 0L) launch {
            val tick = (idleTimeoutMs / 4).coerceIn(1_000L, 5_000L)
            while (isActive) {
                delay(tick)
                if (SystemClock.elapsedRealtime() - lastOutputAt.get() >= idleTimeoutMs) {
                    stalled.set(true)
                    runCatching { proc.destroy() }
                    break
                }
            }
        } else {
            null
        }

        try {
            val body: suspend () -> Result = {
                val sb = StringBuilder()
                val lineBuf = StringBuilder()
                val buffer = ByteArray(8192)
                var truncated = false
                var done = false

                fun flushLine(terminator: Char) {
                    if (lineBuf.isEmpty()) return
                    val line = lineBuf.toString()
                    lineBuf.setLength(0)
                    // 收尾哨兵只用于判定结束，不写进任务日志。
                    // 必须整行精确匹配：bootstrap 的步骤完成标记是
                    // [ZL:DONE:1]，它包含 [ZL:DONE]，用 contains 会误判为
                    // 整个脚本结束，导致第一步之后就被杀掉，永远跑不到第二步。
                    if (doneMark != null && line.trim() == doneMark) {
                        done = true
                        return
                    }
                    if (sb.length < MAX_OUTPUT) sb.append(line).append(terminator)
                    if (sb.length >= MAX_OUTPUT) truncated = true
                    runCatching { onLine?.invoke(line) }
                }

                while (true) {
                    // PTY 从端关闭后 master 端 read() 会返回 EIO（Android 抛
                    // "read failed: EIO (I/O error)"）。这是「进程已结束」的正常信号，
                    // 不是执行失败：必须保留已收集的输出并继续等退出码。旧实现把异常
                    // 抛给外层 catch，成功命令也会变成 exit=-1 且输出全丢。
                    val n = try {
                        proc.inputStream.read(buffer)
                    } catch (e: java.io.IOException) {
                        break
                    }
                    if (n <= 0) break
                    lastOutputAt.set(SystemClock.elapsedRealtime())
                    var i = 0
                    while (i < n) {
                        val ch = buffer[i].toInt().toChar()
                        when (ch) {
                            '\n' -> flushLine('\n')
                            '\r' -> Unit // PTY 的 CRLF 只保留 LF
                            else -> if (lineBuf.length < 64 * 1024) lineBuf.append(ch)
                        }
                        i++
                    }
                    if (done) break
                }
                flushLine('\n')
                if (done) {
                    // 脚本已跑完（哨兵出现）：直接结束，不再等 PTY 关闭，
                    // 避免残留子进程攥着从端导致任务永远停在最后一步。
                    Result(0, sb.toString())
                } else {
                    if (truncated) sb.append("\n... (输出过长已截断)\n")
                    val code = runCatching { PtyNative.waitForProcess(proc.pid) }.getOrDefault(-1)
                    Result(if (stalled.get()) -3 else code, sb.toString())
                }
            }

            if (timeoutMs > 0) {
                withTimeoutOrNull(timeoutMs) { body() } ?: run {
                    proc.destroy()
                    Result(-2, "命令超时")
                }
            } else {
                body()
            }
        } catch (e: CancellationException) {
            proc.destroy()
            throw e
        } catch (e: Exception) {
            proc.destroy()
            Result(-1, e.message ?: "执行失败")
        } finally {
            watchdog?.cancel()
            cancelHook?.dispose()
            runCatching { proc.destroy() }
        }
    }

    suspend fun exec(
        ctx: Context,
        rootfs: File,
        script: String,
        timeoutMs: Long = 0L,
        idleTimeoutMs: Long = 0L,
        doneMark: String? = null,
        onLine: ((String) -> Unit)? = null,
        containerId: String? = null,
        containerUuid: String? = null,
        caDir: File? = null
    ): Result = run(
        ctx, rootfs, listOf("/bin/sh", "-c", script),
        timeoutMs = timeoutMs,
        idleTimeoutMs = idleTimeoutMs,
        doneMark = doneMark,
        onLine = onLine,
        containerId = containerId,
        containerUuid = containerUuid,
        caDir = caDir
    )
}
