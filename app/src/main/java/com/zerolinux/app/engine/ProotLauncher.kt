package com.zerolinux.app.engine

import android.content.Context
import android.util.Log
import com.zerolinux.app.container.CaStore
import com.zerolinux.app.core.AppPaths
import java.io.File

/**
 * PRoot 启动器。命令行结构照搬 LinuxOnAndroid 的 PRootEngine，
 * 但去掉桌面 / VNC / Fedora 修复等本项目用不到的部分。
 */
object ProotLauncher {

    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    private const val TAG = "ProotLauncher"

    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun prootBinary(ctx: Context): File = File(nativeLibDir(ctx), "libproot.so")

    fun isReady(ctx: Context): Boolean {
        val proot = prootBinary(ctx)
        return proot.exists() && proot.length() > 0 && PtyNative.ensureLoaded()
    }

    /** rootfs 解压后补齐 PRoot 运行必需的最小骨架。 */
    fun ensureRootfsSkeleton(ctx: Context, rootfs: File) {
        listOf("dev", "proc", "sys", "tmp", "root", "etc", "var/tmp", "opt").forEach {
            File(rootfs, it).mkdirs()
        }

        val resolv = File(rootfs, "etc/resolv.conf")
        val needResolv = !resolv.exists() || resolv.length() == 0L ||
            runCatching { resolv.readText().contains("nameserver") }.getOrDefault(false).not()
        if (needResolv) {
            runCatching {
                resolv.parentFile?.mkdirs()
                resolv.delete()
                resolv.writeText(
                    """
                    nameserver 223.5.5.5
                    nameserver 119.29.29.29
                    nameserver 8.8.8.8
                    """.trimIndent() + "\n"
                )
            }
        }

        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || hosts.length() == 0L) {
            runCatching {
                hosts.writeText("127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost\n")
            }
        }

        val profile = File(rootfs, "etc/profile.d/00-zerolinux-path.sh")
        if (!profile.exists()) {
            runCatching {
                profile.parentFile?.mkdirs()
                profile.writeText("export PATH=\"$GUEST_PATH:\$PATH\"\n")
            }
        }

        seedCaBundle(rootfs)
    }

    /**
     * Ubuntu/Debian base rootfs 不带 ca-certificates，apt 走 https 会
     * `certificate verify failed`，而 apt-get update 仍返回 0（只打 warning），
     * 结果索引为空、后面所有装包都「找不到」。这里直接把 Android 系统的
     * CA 拷成 guest 的 ca-certificates.crt，让 https 从第一步就可用。
     *
     * [force] = true 时无条件重写（apt/apk 装 ca-certificates 会覆盖聚合文件，
     * 装完必须补回一次）。写完后若容器里有 openssl，就 `openssl rehash`
     * 补 `/etc/ssl/certs/<subject_hash>.0`；subject hash 不能用 MessageDigest
     * 算，没 openssl 就跳过并记日志。
     */
    /** 只落文件：骨架阶段（非挂起上下文）用，没有 openssl 也就不需要 rehash。 */
    fun seedCaBundle(rootfs: File, force: Boolean = false) {
        writeCaBundle(rootfs, force)
    }

    /** 落文件 + 容器里有 openssl 就补 `<subject_hash>.0` 软链。装包覆盖后由 ContainerOps 调用。 */
    suspend fun seedCaBundle(ctx: Context, rootfs: File, force: Boolean = false) {
        if (writeCaBundle(rootfs, force)) rehashCertDir(ctx, rootfs)
    }

    /** @return 是否真的重写了聚合文件。 */
    private fun writeCaBundle(rootfs: File, force: Boolean): Boolean {
        // Debian/apt 认 ca-certificates.crt；Alpine/curl 认 cert.pem；
        // RHEL 系兜底 ca-bundle.crt。三处都写同一份系统 CA，apt/apk 不读 env。
        val targets = listOf(
            File(rootfs, "etc/ssl/certs/ca-certificates.crt"),
            File(rootfs, "etc/ssl/cert.pem"),
            File(rootfs, "etc/pki/tls/certs/ca-bundle.crt")
        )
        if (!force && targets.all { it.exists() && it.length() > 64 * 1024 }) return false

        val srcDir = File("/system/etc/security/cacerts")
        val certs = srcDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
        if (certs.isNullOrEmpty()) {
            Log.w(TAG, "system CA store unavailable, keep existing bundle")
            return false
        }

        val bundle = runCatching {
            buildString {
                certs.forEach { cert ->
                    runCatching { cert.inputStream().bufferedReader().use { append(it.readText()) } }
                    append('\n')
                }
            }
        }.getOrNull()
        if (bundle == null || bundle.length < 64 * 1024) {
            Log.w(TAG, "system CA bundle too small, keep existing bundle")
            return false
        }

        runCatching {
            targets.forEach { dest ->
                dest.parentFile?.mkdirs()
                dest.writeText(bundle)
                android.system.Os.chmod(dest.absolutePath, 0x1A4) // 0644
            }
        }.onFailure {
            Log.w(TAG, "seed ca bundle failed: ${it.message}")
            return false
        }

        return true
    }

    /** 容器里有 openssl 才 rehash；没有就跳过（不自己实现 subject hash）。 */
    private suspend fun rehashCertDir(ctx: Context, rootfs: File) {
        val openssl = listOf("usr/bin/openssl", "bin/openssl", "usr/sbin/openssl", "sbin/openssl")
            .firstOrNull { File(rootfs, it).exists() }
        if (openssl == null) {
            Log.i(TAG, "openssl not installed in rootfs, skip cert rehash")
            return
        }
        runCatching {
            ContainerShell.exec(
                ctx,
                rootfs,
                "openssl rehash /etc/ssl/certs 2>/dev/null || true",
                timeoutMs = 60_000L,
                onLine = { line -> Log.i(TAG, "rehash: $line") }
            )
        }.onFailure { Log.w(TAG, "openssl rehash failed: ${it.message}") }
    }

    /**
     * 给单个容器生成**专属**自签 CA（CN=zerolinux-<uuid 前 8 位>，RSA 2048，
     * 3650 天），落到 `/opt/zerolinux/ca/`，并把证书追加进两份聚合 trust store。
     * 幂等：已有且未过期的 ca.crt 直接复用；容器内没有 openssl 时静默跳过。
     */
    suspend fun ensureContainerCa(ctx: Context, rootfs: File, uuid: String) {
        val script = File(rootfs, "opt/zerolinux/ca-setup.sh")
        runCatching {
            script.parentFile?.mkdirs()
            script.writeText(caScript(uuid))
            script.setReadable(true, false)
        }.onFailure {
            Log.w(TAG, "write container ca script failed: ${it.message}")
            return
        }

        runCatching {
            ContainerShell.exec(
                ctx,
                rootfs,
                "sh /opt/zerolinux/ca-setup.sh",
                timeoutMs = 3 * 60_000L,
                onLine = { line -> Log.i(TAG, "ca: $line") }
            )
        }.onFailure { Log.w(TAG, "container ca failed: ${it.message}") }
    }

    private fun caScript(uuid: String): String {
        val short = uuid.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "local" }
        val cn = "zerolinux-$short"
        return """
            #!/bin/sh
            # 容器专属自签 CA：CN=$cn，RSA 2048，3650 天。key 0644 仅容器内可见。
            CA=/opt/zerolinux/ca
            CRT="@@CA/ca.crt"
            KEY="@@CA/ca.key"
            if ! command -v openssl >/dev/null 2>&1; then
              echo "[ZL:CA:skip] openssl absent"
              exit 0
            fi
            mkdir -p "@@CA"
            if [ -s "@@CRT" ] && [ -s "@@KEY" ] \
               && openssl x509 -in "@@CRT" -noout -checkend 604800 >/dev/null 2>&1; then
              echo "[ZL:CA:reuse] $cn"
            else
              openssl genrsa -out "@@KEY" 2048 >/dev/null 2>&1 || { echo "[ZL:CA:fail] genrsa"; exit 1; }
              chmod 0644 "@@KEY" 2>/dev/null
              openssl req -x509 -new -nodes -key "@@KEY" -sha256 -days 3650 \
                -subj "/CN=$cn" -out "@@CRT" >/dev/null 2>&1 || { echo "[ZL:CA:fail] req"; exit 1; }
              chmod 0644 "@@CRT" 2>/dev/null
              echo "[ZL:CA:created] $cn"
            fi
            # 把容器 CA 追加进聚合 trust store；按 base64 首行判断是否已存在，避免重复。
            append_ca() {
              B="@@1"
              mkdir -p "@@(dirname "@@B")"
              [ -f "@@B" ] || : > "@@B"
              N=@@(sed -n '2p' "@@CRT" | cut -c1-60)
              if [ -n "@@N" ] && tr -d '\r\n' < "@@B" | grep -qF "@@N"; then
                return 0
              fi
              printf '\n' >> "@@B"
              cat "@@CRT" >> "@@B"
              echo "[ZL:CA:append] @@B"
            }
            append_ca /etc/ssl/certs/ca-certificates.crt
            append_ca /etc/ssl/cert.pem
            openssl rehash /etc/ssl/certs >/dev/null 2>&1 || true
            echo "[ZL:CA:done]"
        """.trimIndent()
    }

    /**
     * @param containerId 容器模型里的 id（MT 里看到的目录名）；为空时从
     *   `<rootfs 父目录>/container.json` 读，再退回 uuid。
     * @param containerUuid 容器目录名；为空时用 rootfs 的父目录名。
     */
    fun buildCommand(
        ctx: Context,
        rootfs: File,
        workDir: String = "/root",
        command: List<String>,
        containerId: String? = null,
        containerUuid: String? = null,
        caDir: File? = null
    ): List<String> {
        val cmd = ArrayList<String>(64)
        cmd.add(prootBinary(ctx).absolutePath)
        cmd.add("-0")
        cmd.add("-l")
        cmd.add("-r")
        cmd.add(rootfs.absolutePath)

        // 目录型挂载
        listOf("/proc", "/sys", "/dev", "/dev/pts").forEach { path ->
            if (File(path).exists()) {
                cmd.add("-b")
                cmd.add(path)
            }
        }

        // 文件型挂载：PRoot 不需要预先建挂载点
        listOf(
            "/proc/self/fd" to "/dev/fd",
            "/proc/self/fd/0" to "/dev/stdin",
            "/proc/self/fd/1" to "/dev/stdout",
            "/proc/self/fd/2" to "/dev/stderr",
            "/proc/self" to "/proc/1"
        ).forEach { (host, guest) ->
            if (File(host).exists()) {
                cmd.add("-b")
                cmd.add("$host:$guest")
            }
        }

        // SELinux 在 PRoot 下不可用，用空目录盖掉
        val selinux = File("/sys/fs/selinux")
        if (selinux.exists()) {
            val empty = File(ctx.filesDir, "empty_selinux").apply { mkdirs() }
            cmd.add("-b")
            cmd.add("${empty.absolutePath}:/sys/fs/selinux")
        }

        // 外部存储：每个容器一个专属目录（MT 可见），旧共享目录只保留入口
        val appExternal = ctx.getExternalFilesDir(null)
        // 真实 SDCard 优先（/storage/emulated/0），App 私有目录兜底；
        // 未授权 MANAGE_EXTERNAL_STORAGE 时 guest 内 cd /sdcard 会 Permission denied。
        val realSd = File("/storage/emulated/0")
        val sdRoot: File? = if (realSd.isDirectory) {
            realSd
        } else {
            appExternal?.let { File(it, "sdcard").apply { mkdirs() } }
        }
        if (sdRoot != null) {
            cmd.add("-b")
            cmd.add("${sdRoot.absolutePath}:/sdcard")
            cmd.add("-b")
            cmd.add("${sdRoot.absolutePath}:/mnt/sdcard")
        }
        // /external 指向 containers/<id>：目录名跟着容器 id 走，改 id 后目录一并改名。
        val uuid = containerUuid ?: rootfs.parentFile?.name
        if (uuid != null) {
            val id = containerId ?: com.zerolinux.app.files.ContainerExternal.resolveId(ctx, uuid)
            val perContainer = com.zerolinux.app.files.ContainerExternal.dir(ctx, id, uuid)
            cmd.add("-b")
            cmd.add(
                "${perContainer.absolutePath}:${com.zerolinux.app.files.ContainerExternal.GUEST_PATH}"
            )
        }
        // 旧版本所有容器共用的目录：旧文件仍从这里可见，不再当作 /external。
        if (appExternal != null) {
            cmd.add("-b")
            cmd.add(
                "${appExternal.absolutePath}:" +
                    com.zerolinux.app.files.ContainerExternal.SHARED_GUEST_PATH
            )
        }
        val storageRoot = File("/storage")
        if (storageRoot.exists()) {
            cmd.add("-b")
            cmd.add("/storage")
        }

        // nativeLibraryDir：PRoot loader 与 talloc / shmem 从这里加载
        val libDir = nativeLibDir(ctx)
        if (libDir.exists()) {
            cmd.add("-b")
            cmd.add(libDir.absolutePath)

            val loader = File(libDir, "libproot_loader.so")
            if (loader.exists()) {
                runCatching {
                    val target = File(rootfs, "usr/local/lib/libproot_loader.so")
                    target.parentFile?.mkdirs()
                    if (!target.exists() || target.length() != loader.length()) {
                        loader.copyTo(target, overwrite = true)
                        android.system.Os.chmod(target.absolutePath, 0x1ED) // 0755
                    }
                }
            }
        }

        // Android 10+ 读不到 /proc/stat 等，喂一份合成的
        val fakeProc = ensureFakeProc(ctx)
        listOf("stat", "version", "vmstat").forEach { name ->
            val f = File(fakeProc, name)
            if (f.exists()) {
                cmd.add("-b")
                cmd.add("${f.absolutePath}:/proc/$name")
            }
        }

        // guest /tmp 必须按容器隔离：apt/apk 日志、Node/Go 临时包都写在
        // /tmp，如果所有容器共用 AppPaths.tmpDir，Alpine 的报错会读到
        // Ubuntu 留下的日志，甚至复用到别的容器的半截下载文件。
        val tmp = (uuid?.let { File(AppPaths.containerDir(ctx, it), "tmp") }
            ?: AppPaths.tmpDir(ctx)).apply { mkdirs() }
        cmd.add("-b")
        cmd.add("${tmp.absolutePath}:/tmp")

        // PROOT_TMP_DIR 有时改用 /data/local/tmp，两个路径都挂上更稳
        val altTmp = File("/data/local/tmp")
        if (altTmp.exists()) {
            cmd.add("-b")
            cmd.add(altTmp.absolutePath)
        }

        val cache = ctx.cacheDir
        if (cache.exists()) {
            cmd.add("-b")
            cmd.add(cache.absolutePath)
        }

        val l2s = AppPaths.l2sDir(ctx).apply { mkdirs() }
        cmd.add("-b")
        cmd.add(l2s.absolutePath)
        val l2sAlias = l2s.absolutePath
            .replace("/data/user/0/", "/data/data/")
        if (l2sAlias != l2s.absolutePath) {
            if (File(l2sAlias).exists()) {
                cmd.add("-b")
                cmd.add(l2sAlias)
            }
        }

        // 官方 ca-certificates 资产：每个容器一份，PRoot 启动时把 certs 目录
        // 直接绑到 guest 的 /etc/ssl/certs。这样 apt/apk/curl 在镜像源脚本
        // 执行前就已经信任官方根证书，不需要先下载 ca-certificates 包。
        val caRoot = caDir ?: uuid?.let { runCatching { CaStore.ensureForUuid(ctx, it) }.getOrNull() }
        if (caRoot != null) {
            CaStore.ensureGuestDirs(rootfs)
            val certDir = File(caRoot, "certs")
            if (certDir.isDirectory) {
                cmd.add("-b")
                cmd.add("${certDir.absolutePath}:/etc/ssl/certs")
            }
            val bundle = File(caRoot, "ca-certificates.crt")
            if (bundle.isFile) {
                cmd.add("-b")
                cmd.add("${bundle.absolutePath}:/etc/ssl/cert.pem")
                cmd.add("-b")
                cmd.add("${bundle.absolutePath}:/etc/pki/tls/certs/ca-bundle.crt")
            }
        }

        File(rootfs, workDir.removePrefix("/")).mkdirs()
        cmd.add("-w")
        cmd.add(workDir)

        cmd.addAll(command)
        return cmd
    }

    fun buildEnv(
        ctx: Context,
        rootfs: File,
        loginUser: String = "root",
        extra: Map<String, String> = emptyMap()
    ): Array<String> {
        val libDir = nativeLibDir(ctx)
        val env = LinkedHashMap<String, String>()
        env["LD_LIBRARY_PATH"] = libDir.absolutePath
        env["PROOT_TMP_DIR"] = AppPaths.tmpDir(ctx).absolutePath
        env["PROOT_L2S_DIR"] = AppPaths.l2sDir(ctx).absolutePath
        env["PROOT_UNSETENV"] = "LD_LIBRARY_PATH:TMPDIR:TMP"
        env["PROOT_NO_SECCOMP"] = "1"
        env["PROOT_FORCE_SETID"] = "1"
        // 不启用 PROOT_LINK2SYMLINK：它会把大字号文件搬出 rootfs 放进全局 l2s，
        // 导致备份/克隆无法自包含。让 PRoot 保持容器目录内自足。

        File(libDir, "libproot_loader.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER"] = it.absolutePath
        }
        File(libDir, "libproot_loader32.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER32"] = it.absolutePath
        }

        val home = if (loginUser == "root") "/root" else "/home/$loginUser"
        env["HOME"] = home
        env["USER"] = loginUser
        env["LOGNAME"] = loginUser
        env["PATH"] = GUEST_PATH
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = ""
        env["TMPDIR"] = "/tmp"
        env["TMP"] = "/tmp"
        env["SHELL"] = "/bin/sh"
        // 统一 CA 环境变量：curl/python/git/node 会读，apt/apk 不读（靠文件落位）。
        val caFile = "/etc/ssl/certs/ca-certificates.crt"
        env["SSL_CERT_FILE"] = caFile
        env["CURL_CA_BUNDLE"] = caFile
        env["REQUESTS_CA_BUNDLE"] = caFile
        env["PIP_CERT"] = caFile
        env["GIT_SSL_CAINFO"] = caFile
        env["GIT_SSL_CAPATH"] = "/etc/ssl/certs"
        env["NODE_EXTRA_CA_CERTS"] = caFile
        env["SSL_CERT_DIR"] = "/etc/ssl/certs"
        extra.forEach { (k, v) -> env[k] = v }

        // 只把 PRoot 运行必需的东西传进去，避免宿主 Android 的环境变量污染 guest
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    fun start(
        ctx: Context,
        rootfs: File,
        command: List<String>,
        workDir: String = "/root",
        cols: Int = 160,
        rows: Int = 48,
        extraEnv: Map<String, String> = emptyMap(),
        containerId: String? = null,
        containerUuid: String? = null,
        caDir: File? = null
    ): PtyProcess? {
        if (!PtyNative.ensureLoaded()) return null
        if (!prootBinary(ctx).exists()) return null
        ensureRootfsSkeleton(ctx, rootfs)

        val argv = buildCommand(
            ctx, rootfs, workDir, command, containerId, containerUuid, caDir
        )
        val env = buildEnv(ctx, rootfs, extra = extraEnv)
        val pidOut = IntArray(1)
        val fd = runCatching {
            PtyNative.createSubprocess(
                argv[0], argv.toTypedArray(), env, rootfs.absolutePath, cols, rows, pidOut
            )
        }.getOrDefault(-1)
        if (fd < 0) return null
        return PtyProcess(fd, pidOut[0])
    }

    /** 用 guest 里的 /bin/sh -lc 跑一段脚本。 */
    fun startScript(ctx: Context, rootfs: File, script: String): PtyProcess? =
        start(ctx, rootfs, listOf("/bin/sh", "-c", script))

    private fun ensureFakeProc(ctx: Context): File {
        val dir = File(ctx.filesDir, "fake_proc").apply { mkdirs() }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val stat = File(dir, "stat")
        val statText = buildString {
            appendLine("cpu  2000 0 2000 200000 0 0 0 0 0 0")
            repeat(cores) { i ->
                val share = 2000 / cores
                appendLine("cpu$i $share 0 $share ${200000 / cores} 0 0 0 0 0 0")
            }
            appendLine("intr 1000 0 0")
            appendLine("ctxt 5000")
            appendLine("btime ${System.currentTimeMillis() / 1000 - 86400}")
            appendLine("processes 1000")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 1000 0 0 0 0 0 0 0 0 0")
        }
        runCatching { stat.writeText(statText) }

        val version = File(dir, "version")
        if (!version.exists() || version.length() == 0L) {
            runCatching {
                version.writeText(
                    "Linux version 6.1.0-android-proot (gcc version 11.4.0) #1 SMP PREEMPT\n"
                )
            }
        }

        val vmstat = File(dir, "vmstat")
        if (!vmstat.exists() || vmstat.length() == 0L) {
            runCatching {
                vmstat.writeText(
                    "nr_free_pages 100000\nnr_alloc_batch 1000\npgpgin 50000\npgpgout 50000\npswpin 0\npswpout 0\n"
                )
            }
        }
        return dir
    }
}
