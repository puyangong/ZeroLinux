package com.zerolinux.app.container

import android.content.Context
import android.system.Os
import com.zerolinux.app.core.AppPaths
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * 官方 ca-certificates 资产库。
 *
 * APK 里为 Ubuntu / Debian / Kali / Alpine 各带了一份从官方 ca-certificates
 * 包中提取的 Mozilla 根证书（`assets/ca/<distro>/certs` 下的单个 .crt）以及聚合后的
 * `ca-certificates.crt`。初始化时复制到容器目录 `ca/`，PRoot 启动时用 `-b`
 * 把 `ca/certs` 绑到 guest 的 `/etc/ssl/certs`，这样镜像源脚本从第一步就能
 * 走 HTTPS，不依赖 rootfs 里是否已经装了 ca-certificates。
 *
 * 每个容器独立一份，容器删除时随 `containers/<uuid>` 一起删除；克隆/备份
 * 不复制这份缓存，目标容器首次启动时会从 APK 资产重新生成。
 */
object CaStore {

    private const val ASSET_ROOT = "ca"
    private val LOCK = Any()

    /** 返回容器 CA 根目录；资产缺失时返回 null（调用方回退到 rootfs 内的证书）。 */
    fun ensure(ctx: Context, distro: DistroId, uuid: String): File? {
        val app = ctx.applicationContext
        val base = File(AppPaths.containerDir(app, uuid), "ca")
        val certs = File(base, "certs")
        val bundle = File(base, "ca-certificates.crt")
        val marker = File(base, ".ready")

        if (marker.isFile && bundle.isFile && bundle.length() > 0L && certs.isDirectory) {
            return base
        }

        return synchronized(LOCK) {
            base.mkdirs()
            certs.mkdirs()
            RandomAccessFile(File(base, ".lock"), "rw").use { raf ->
                raf.channel.lock().use {
                    if (marker.isFile && bundle.isFile && bundle.length() > 0L && certs.isDirectory) {
                        return@synchronized base
                    }
                    val key = distro.name.lowercase(Locale.US)
                    val names = runCatching {
                        app.assets.list("$ASSET_ROOT/$key/certs").orEmpty().filter { it.endsWith(".crt") }
                    }.getOrDefault(emptyList())
                    if (names.isEmpty()) return@synchronized null

                    names.forEach { name ->
                        runCatching {
                            app.assets.open("$ASSET_ROOT/$key/certs/$name").use { input ->
                                File(certs, name).outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }

                    val assetBundle = runCatching {
                        app.assets.open("$ASSET_ROOT/$key/ca-certificates.crt")
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    }.getOrNull()

                    val content = assetBundle?.takeIf { it.contains("BEGIN CERTIFICATE") }
                        ?: buildString {
                            names.sorted().forEach { name ->
                                val text = runCatching {
                                    File(certs, name).readText(Charsets.UTF_8)
                                }.getOrNull().orEmpty()
                                append(text)
                                if (text.isNotEmpty() && !text.endsWith('\n')) append('\n')
                            }
                        }
                    if (content.isBlank()) return@synchronized null

                    bundle.writeText(content)
                    File(certs, "ca-certificates.crt").writeText(content)
                    chmod(bundle)
                    File(certs, "ca-certificates.crt").let { chmod(it) }
                    marker.writeText("$key\n")
                    base
                }
            }
        }
    }

    /** 从容器配置取发行版；容器不存在时返回 null。 */
    fun ensureForUuid(ctx: Context, uuid: String): File? {
        val cfg = runCatching { ContainerRepo(ctx).find(uuid) }.getOrNull() ?: return null
        return ensure(ctx, cfg.distro, uuid)
    }

    /** 让 rootfs 内的目录存在；PRoot 绑定时目标路径需要可写/可挂载。 */
    fun ensureGuestDirs(rootfs: File) {
        runCatching {
            File(rootfs, "etc/ssl/certs").mkdirs()
            File(rootfs, "etc/pki/tls/certs").mkdirs()
            // PRoot 的文件 bind 需要 guest 侧目标存在；先放空文件，启动时再覆盖。
            File(rootfs, "etc/ssl/cert.pem").let {
                it.parentFile?.mkdirs()
                if (!it.exists()) it.writeText("")
            }
            File(rootfs, "etc/pki/tls/certs/ca-bundle.crt").let {
                it.parentFile?.mkdirs()
                if (!it.exists()) it.writeText("")
            }
        }
    }

    private fun chmod(file: File) {
        runCatching { Os.chmod(file.absolutePath, 0x1A4) } // 0644
    }
}
