package com.zerolinux.app.core

import android.content.Context
import java.io.File

/**
 * ZeroLinux 的全部磁盘布局都从这里出发，避免各进程各自拼路径。
 *
 * filesDir/
 *   zerolinux/settings/global.json     全局设置
 *   zerolinux/l2s/                     PRoot hardlink -> symlink 存储
 *   zerolinux/tmp/                     PROOT_TMP_DIR
 *   zerolinux/logs/
 *   zerolinux/scroll.json              文件管理器滚动缓存
 *   containers/<uuid>/rootfs           容器根文件系统
 *   containers/<uuid>/container.json   单容器配置（:terminal 只读）
 */
object AppPaths {

    fun root(ctx: Context): File = File(ctx.filesDir, "zerolinux")

    fun settingsDir(ctx: Context): File = File(root(ctx), "settings")

    fun globalJson(ctx: Context): File = File(settingsDir(ctx), "global.json")

    fun containersDir(ctx: Context): File = File(ctx.filesDir, "containers")

    fun containerDir(ctx: Context, uuid: String): File = File(containersDir(ctx), uuid)

    fun containerRootfs(ctx: Context, uuid: String): File = File(containerDir(ctx, uuid), "rootfs")

    fun containerJson(ctx: Context, uuid: String): File = File(containerDir(ctx, uuid), "container.json")

    fun l2sDir(ctx: Context): File = File(root(ctx), "l2s")

    fun tmpDir(ctx: Context): File = File(root(ctx), "tmp")

    fun logsDir(ctx: Context): File = File(root(ctx), "logs")

    fun scrollJson(ctx: Context): File = File(root(ctx), "scroll.json")

    fun ensure(ctx: Context) {
        listOf(
            root(ctx),
            settingsDir(ctx),
            containersDir(ctx),
            l2sDir(ctx),
            tmpDir(ctx),
            logsDir(ctx)
        ).forEach { dir -> if (!dir.exists()) dir.mkdirs() }

        val noMedia = File(tmpDir(ctx), ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
    }
}
