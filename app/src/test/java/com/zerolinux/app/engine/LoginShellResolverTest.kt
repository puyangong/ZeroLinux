package com.zerolinux.app.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 纯 JVM 测试：只依赖临时目录里的文件，不碰 Android API。
 *
 * 覆盖方向键回归的三种镜像形态：Debian 系（bash）、Alpine（无 bash）、
 * passwd 里是 nologin 的异常容器。
 */
class LoginShellResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(): File = tmp.newFolder("rootfs")

    private fun writePasswd(rootfs: File, shell: String, user: String = "root") {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "passwd").writeText(
            "root:x:0:0:root:/root:$shell\n" +
                "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n"
        )
    }

    private fun touch(rootfs: File, path: String) {
        val f = File(rootfs, path)
        f.parentFile?.mkdirs()
        f.writeText("")
    }

    @Test
    fun `debian family uses passwd login shell bash`() {
        val rootfs = rootfs()
        writePasswd(rootfs, "/bin/bash")
        touch(rootfs, "bin/bash")
        touch(rootfs, "bin/sh")

        assertEquals(listOf("/bin/bash", "-l"), resolveLoginShell(rootfs))
    }

    @Test
    fun `usr merged rootfs finds bash under usr bin`() {
        val rootfs = rootfs()
        writePasswd(rootfs, "/bin/bash")
        // bin 目录缺失或是指向 usr/bin 的 guest 绝对符号链接时，bash 只在 usr/bin 下。
        touch(rootfs, "usr/bin/bash")

        assertEquals(listOf("/bin/bash", "-l"), resolveLoginShell(rootfs))
    }

    @Test
    fun `alpine without bash keeps bin sh`() {
        val rootfs = rootfs()
        writePasswd(rootfs, "/bin/sh")
        touch(rootfs, "bin/sh")
        touch(rootfs, "bin/busybox")

        assertEquals(listOf("/bin/sh", "-l"), resolveLoginShell(rootfs))
    }

    @Test
    fun `alpine passwd ash keeps bin ash`() {
        val rootfs = rootfs()
        writePasswd(rootfs, "/bin/ash")
        touch(rootfs, "bin/ash")

        assertEquals(listOf("/bin/ash", "-l"), resolveLoginShell(rootfs))
    }

    @Test
    fun `nologin falls back to bash`() {
        val rootfs = rootfs()
        writePasswd(rootfs, "/usr/sbin/nologin")
        touch(rootfs, "bin/bash")

        assertEquals(listOf("/bin/bash", "-l"), resolveLoginShell(rootfs))
    }

    @Test
    fun `passwd shell missing in rootfs falls back to bash`() {
        val rootfs = rootfs()
        writePasswd(rootfs, "/bin/zsh")
        touch(rootfs, "usr/bin/bash")

        assertEquals(listOf("/bin/bash", "-l"), resolveLoginShell(rootfs))
    }

    @Test
    fun `no passwd and no bash falls back to bin sh`() {
        val rootfs = rootfs()
        touch(rootfs, "usr/bin/sh")

        assertEquals(listOf("/bin/sh", "-l"), resolveLoginShell(rootfs))
    }
}
