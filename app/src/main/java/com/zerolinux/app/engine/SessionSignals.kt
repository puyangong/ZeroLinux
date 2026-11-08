package com.zerolinux.app.engine

import android.content.Context
import com.zerolinux.app.core.AppPaths
import java.io.File

/**
 * 跨进程会话信号。
 *
 * `ContainerOps.restart` 在主进程执行，真正持有 PTY 的 SessionRegistry 在
 * `:terminal`。主进程没法直接调用另一个进程里的单例，只能落一个标记文件，
 * 由终端页轮询消费后清理自己的会话并重建 shell。
 */
object SessionSignals {

    private const val RESTART_DIR = "session-restart"
    private const val REMOVE_DIR = "session-remove"

    /** 容器重启：终端页收到后应清掉该容器全部会话，并重建当前 shell。 */
    fun requestRestart(ctx: Context, uuid: String) {
        write(ctx, RESTART_DIR, uuid)
    }

    /** 容器删除：终端页收到后只清会话，不重建。 */
    fun requestRemove(ctx: Context, uuid: String) {
        write(ctx, REMOVE_DIR, uuid)
    }

    fun consumeRestarts(ctx: Context): Set<String> = consume(ctx, RESTART_DIR)

    fun consumeRemoves(ctx: Context): Set<String> = consume(ctx, REMOVE_DIR)

    private fun write(ctx: Context, dirName: String, uuid: String) {
        if (uuid.isBlank()) return
        runCatching {
            val dir = File(AppPaths.root(ctx), dirName)
            dir.mkdirs()
            // 先写临时文件再 rename：终端进程可能正好在消费目录，
            // 直接写目标文件会被它删掉，导致重启/删除信号丢失。
            val tmp = File(dir, "$uuid.tmp")
            tmp.writeText(System.currentTimeMillis().toString())
            val target = File(dir, uuid)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun consume(ctx: Context, dirName: String): Set<String> {
        val dir = File(AppPaths.root(ctx), dirName)
        val files = dir.listFiles() ?: return emptySet()
        val out = HashSet<String>()
        files.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                runCatching { file.delete() }
                return@forEach
            }
            if (file.isFile) out += file.name
            runCatching { file.delete() }
        }
        return out
    }
}
