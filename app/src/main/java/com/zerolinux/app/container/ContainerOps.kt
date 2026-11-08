package com.zerolinux.app.container

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.zerolinux.app.R
import com.zerolinux.app.ai.AiIntents
import com.zerolinux.app.core.AppPaths
import com.zerolinux.app.engine.ContainerShell
import com.zerolinux.app.engine.ProotLauncher
import com.zerolinux.app.engine.SessionSignals
import com.zerolinux.app.files.ContainerExternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "ContainerOps"

/**
 * 容器生命周期操作：创建、初始化、克隆、删除、备份、恢复。
 *
 * 这些操作都很慢（下载几十 MB、解压上万个文件、在 proot 里跑 apt），
 * 因此全部声明为 suspend，并且只通过 [onProgress] 回报进度，不直接碰 UI。
 */
class ContainerOps(private val ctx: Context) {

    enum class Stage { PREPARE, DOWNLOAD, EXTRACT, MIRROR, BOOTSTRAP, COPY, ARCHIVE, RESTORE }

    data class Progress(
        val stage: Stage,
        val step: Int = 0,
        val total: Int = 0,
        val detail: String = "",
        /** 当前步骤内部百分比；-1 表示沿用 step/total 的整任务进度。 */
        val subPercent: Int = -1
    ) {
        val percent: Int
            get() = if (subPercent >= 0) subPercent.coerceIn(0, 100)
            else if (total <= 0) 0 else (step.coerceIn(0, total) * 100 / total)
    }

    private val repo = ContainerRepo(ctx)

    fun repo(): ContainerRepo = repo

    // -------------------------------------------------------- 断点重装标记

    private val ROOTFS_MARKER = ".zl-rootfs-ok"

    /** 解压完成的落盘标记：便于诊断，也留给以后做「哪些目录可信」的判断。 */
    private fun markRootfsComplete(rootfs: File) {
        runCatching { File(rootfs, ROOTFS_MARKER).writeText("ok") }
    }

    /**
     * rootfs 是否已经可用。老容器没有标记文件，退化为「bin/sh 存在」，
     * 避免升级后把所有已装好的容器重下一遍。
     */
    private fun rootfsComplete(rootfs: File): Boolean {
        if (!rootfs.isDirectory) return false
        return File(rootfs, "bin/sh").exists()
    }

    // ---------------------------------------------------------------- create

    /**
     * 建一个容器：先把配置落库拿到 uuid（这样 UI 立刻能看到它），
     * 再下载并解压 rootfs。失败时容器保留，lastError 记下原因，可以重试。
     */
    suspend fun create(
        distro: DistroId,
        id: String? = null,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        val cfg = ContainerConfig(
            uuid = UUID.randomUUID().toString(),
            id = id?.trim()?.takeIf { it.isNotEmpty() } ?: repo.suggestId(distro),
            distro = distro,
            abi = abi,
            createdAt = System.currentTimeMillis()
        )
        repo.create(cfg)
        // 外接目录用 id 命名，创建即建好，MT 里立刻能看到。
        ContainerExternal.dir(ctx, cfg.id, cfg.uuid)

        var stage = Stage.PREPARE
        try {
            stage = Stage.PREPARE
            onProgress(Progress(Stage.PREPARE, 0, 1, "resolve $distro"))
            // 候选镜像（国内优先 + 官方兜底），由下载器逐个尝试。
            val sources = DistroImages.resolveAll(distro, abi)
            val source = sources.first()

            val dir = AppPaths.containerDir(ctx, cfg.uuid)
            val rootfs = AppPaths.containerRootfs(ctx, cfg.uuid)
            if (rootfs.exists()) rootfs.deleteRecursively()
            rootfs.mkdirs()

            stage = Stage.DOWNLOAD
            onProgress(Progress(Stage.DOWNLOAD, 0, source.approxBytes.toInt(), source.fileName))
            val outcome = RootfsDownloader().downloadAll(sources, dir) { read, total ->
                onProgress(Progress(Stage.DOWNLOAD, read.toInt(), total.toInt(), source.fileName))
            }

            stage = Stage.EXTRACT
            onProgress(Progress(Stage.EXTRACT, 0, 1, outcome.file.name))
            RootfsExtractor.extract(outcome.file, rootfs) { entryIndex ->
                if (entryIndex % 200L == 0L) {
                    onProgress(Progress(Stage.EXTRACT, entryIndex.toInt(), 0, ""))
                }
            }
            outcome.file.delete()

            // 必须在 ensureRootfsSkeleton 之前：骨架会先建出 dev/proc/sys 等目录，
            // 之后就无法判断「解压结果只有一层包装目录」了。
            flattenNestedRoot(rootfs)

            ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
            markRootfsComplete(rootfs)

            if (repo.find(cfg.uuid) == null) {
                throw CancellationException("container deleted during rootfs install")
            }
            val done = cfg.copy(rootfsReady = true, lastError = "")
            repo.save(done)
            onProgress(Progress(Stage.EXTRACT, 1, 1, "rootfs ready"))
            done
        } catch (e: CancellationException) {
            // 用户中途删除容器：取消不是失败，不能把 lastError 写回去，
            // 更不能让已删除的配置被 repo.save 重新加回列表。
            throw e
        } catch (t: Throwable) {
            // 结构化落库：阶段 + 消息（下载失败已带 HTTP 状态码）。
            val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            if (repo.find(cfg.uuid) != null) repo.save(cfg.copy(lastError = "${stage}: $message"))
            throw t
        }
    }

    /**
     * 只补 rootfs，不动 initialized：给「配置已落库、但下载/解压中途被打断」的容器用。
     * 已就绪（rootfsReady && 目录存在）时只补骨架立即返回，不重复下载。
     * 与 create() 的下半段保持同一套下载/解压/骨架顺序，避免两处漂移。
     */
    suspend fun ensureRootfs(
        uuid: String,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val cfg = repo.find(uuid) ?: throw IllegalStateException("container not found: $uuid")
        val rootfs = AppPaths.containerRootfs(ctx, uuid)
        if (cfg.rootfsReady && rootfs.isDirectory) {
            ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
            return@withContext cfg
        }
        // 断点续装：rootfs 已经解压出可用 shell（可能只差 bootstrap / CLI），
        // 直接标记 rootfsReady 返回，绝不再删目录重下。这是「安装失败后重试
        // 保留已安装进度」的关键：旧实现在这里无条件 deleteRecursively()。
        if (rootfsComplete(rootfs)) {
            flattenNestedRoot(rootfs)
            ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
            markRootfsComplete(rootfs)
            val current = repo.find(uuid)
                ?: throw CancellationException("container deleted during rootfs install")
            val kept = current.copy(rootfsReady = true, lastError = "")
            repo.save(kept)
            onProgress(Progress(Stage.EXTRACT, 1, 1, "rootfs kept"))
            return@withContext kept
        }

        val abi = cfg.abi.ifBlank { Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a" }
        repo.save(cfg.copy(rootfsReady = false, lastError = ""))
        try {
            onProgress(Progress(Stage.PREPARE, 0, 1, "resolve ${cfg.distro}"))
            // 与 create() 一致：候选镜像列表 + 逐个尝试。
            val sources = DistroImages.resolveAll(cfg.distro, abi)
            val source = sources.first()

            val dir = AppPaths.containerDir(ctx, uuid)
            if (rootfs.exists()) rootfs.deleteRecursively()
            rootfs.mkdirs()

            onProgress(Progress(Stage.DOWNLOAD, 0, source.approxBytes.toInt(), source.fileName))
            val outcome = RootfsDownloader().downloadAll(sources, dir) { read, total ->
                onProgress(Progress(Stage.DOWNLOAD, read.toInt(), total.toInt(), source.fileName))
            }

            onProgress(Progress(Stage.EXTRACT, 0, 1, outcome.file.name))
            RootfsExtractor.extract(outcome.file, rootfs) { entryIndex ->
                if (entryIndex % 200L == 0L) {
                    onProgress(Progress(Stage.EXTRACT, entryIndex.toInt(), 0, ""))
                }
            }
            outcome.file.delete()

            flattenNestedRoot(rootfs)
            ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
            markRootfsComplete(rootfs)

            val latest = repo.find(uuid)
                ?: throw CancellationException("container deleted during rootfs install")
            val done = latest.copy(rootfsReady = true, lastError = "")
            repo.save(done)
            onProgress(Progress(Stage.EXTRACT, 1, 1, "rootfs ready"))
            done
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (repo.find(uuid) != null) {
                repo.find(uuid)?.let {
                    repo.save(it.copy(lastError = t.message ?: t.javaClass.simpleName))
                }
            }
            throw t
        }
    }

    // ------------------------------------------------------------ initialize

    /**
     * 换源 + 装软件栈。脚本先落到 rootfs 里，断点状态在
     * `/opt/zerolinux/.state`，中断后再次调用会从上次的步骤继续。
     */
    suspend fun initialize(
        uuid: String,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val cfg = repo.find(uuid) ?: throw IllegalStateException("container not found: $uuid")
        val rootfs = AppPaths.containerRootfs(ctx, uuid)
        if (!rootfs.isDirectory) throw IllegalStateException("rootfs missing: $uuid")

        ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
        val optDir = File(rootfs, "opt/zerolinux").apply { mkdirs() }

        // 重试时保留已完成的步骤进度：只有全新容器（或上一次停在换源阶段）
        // 才把 initStep 归零到 "mirror"；bootstrap:N/5 要留着给 UI 显示。
        val keepStep = cfg.initStep.takeIf { it.startsWith("bootstrap:") }
        if (repo.find(uuid) == null) throw CancellationException("container deleted during initialize")
        repo.save(cfg.copy(initStep = keepStep ?: "mirror", lastError = ""))
        clearGuestLogs(ctx, uuid)

        onProgress(Progress(Stage.MIRROR, 0, BootstrapScript.totalSteps(cfg.distro), ""))
        // 宿主机并行测速后得到本次安装的 HTTPS 镜像计划，容器内不再探测。
        val plan = MirrorConfigurator.plan(ctx, cfg.distro)
        val caDir = runCatching { CaStore.ensure(ctx, cfg.distro, uuid) }.getOrNull()
        val mirrorScript = MirrorConfigurator.script(cfg.distro, plan)
        val mirrorFile = File(optDir, "mirror.sh")
        mirrorFile.writeText(mirrorScript)
        mirrorFile.setReadable(true, false)

        var mirrorResult = ContainerShell.exec(
            ctx, rootfs, "sh /opt/zerolinux/mirror.sh; echo " + ContainerShell.DONE_MARK,
            timeoutMs = 10 * 60_000L,
            idleTimeoutMs = 2 * 60_000L,
            doneMark = ContainerShell.DONE_MARK,
            containerId = cfg.id,
            containerUuid = uuid,
            caDir = caDir,
            onLine = { line -> onProgress(Progress(Stage.MIRROR, 0, BootstrapScript.totalSteps(cfg.distro), line)) }
        )
        if (mirrorResult.exitCode == -3 || mirrorResult.exitCode == -2) {
            // 换源脚本幂等：卡住/超时后自动再跑一次，避免整个安装停在 mirror。
            delay(1_500L)
            mirrorResult = ContainerShell.exec(
                ctx, rootfs, "sh /opt/zerolinux/mirror.sh; echo " + ContainerShell.DONE_MARK,
                timeoutMs = 10 * 60_000L,
                idleTimeoutMs = 2 * 60_000L,
                doneMark = ContainerShell.DONE_MARK,
                containerId = cfg.id,
                containerUuid = uuid,
                caDir = caDir,
                onLine = { line -> onProgress(Progress(Stage.MIRROR, 0, BootstrapScript.totalSteps(cfg.distro), line)) }
            )
        }

        if (mirrorResult.exitCode > 0) {
            // 换源脚本自己会回滚，这里只记录，不让整个初始化中断：
            // 官方源通常仍然可用，装包还能继续。
            repo.save(
                repo.find(uuid)?.copy(lastError = "mirror rc=${mirrorResult.exitCode}") ?: cfg
            )
        }

        onProgress(Progress(Stage.BOOTSTRAP, 0, BootstrapScript.totalSteps(cfg.distro), ""))
        val bootstrapFile = File(optDir, "bootstrap.sh")
        bootstrapFile.writeText(BootstrapScript.script(cfg.distro, plan))
        bootstrapFile.setReadable(true, false)

        val total = BootstrapScript.totalSteps(cfg.distro)
        var step = 0
        var stepPercent = -1
        // bootstrap 的进度只走内存回调，进程被杀（或用户切走再回来）时
        // initStep 会一直停在 "mirror"，列表上就看不出已经走到第几步。
        // 这里按「step 变化」落库，避免每行脚本输出都写一次 containers.json。
        var persistedStep = -1
        fun report(step: Int, msg: String, percent: Int = stepPercent) {
            if (step > 0 && step != persistedStep) {
                persistedStep = step
                runCatching {
                    repo.find(uuid)?.let {
                        repo.save(it.copy(initStep = "bootstrap:$step/$total"))
                    }
                }
            }
            onProgress(Progress(Stage.BOOTSTRAP, step, total, msg, percent))
        }

        // 上限 60 分钟：单步 timeout 之和（update 180s + install 600s ×2 次重试
        // + node/rust/go 下载）可能超过 20 分钟；20 分钟会在 install 中途杀掉进程，
        // 留下半装的 dpkg 状态 —— 用户感知就是「必须初始化两次」。
        // 另外 5 分钟没有任何输出视为卡死：销毁子进程后从 .state 断点自动重试。
        var attempt = 0
        var result: ContainerShell.Result
        while (true) {
            attempt++
            result = ContainerShell.exec(
                ctx, rootfs, "sh /opt/zerolinux/bootstrap.sh; echo " + ContainerShell.DONE_MARK,
                timeoutMs = 60 * 60_000L,
                idleTimeoutMs = 15 * 60_000L,
                doneMark = ContainerShell.DONE_MARK,
                containerId = cfg.id,
                containerUuid = uuid,
                caDir = caDir,
                onLine = { line ->
                    if (line.contains(BootstrapScript.MARK_STEP)) {
                        step = line.substringAfter(BootstrapScript.MARK_STEP)
                            .substringBefore(']').toIntOrNull() ?: step
                    }
                    if (line.contains(BootstrapScript.MARK_PROGRESS)) {
                        val payload = line.substringAfter(BootstrapScript.MARK_PROGRESS)
                            .substringBefore(']')
                        val parts = payload.split(':')
                        parts.getOrNull(0)?.toIntOrNull()?.let { step = it }
                        parts.getOrNull(1)?.toIntOrNull()?.let {
                            stepPercent = it.coerceIn(0, 100)
                        }
                    }
                    val detail = if (line.contains(BootstrapScript.MARK_PROGRESS)) {
                        "step $step ${stepPercent.coerceAtLeast(0)}%"
                    } else {
                        line
                    }
                    report(step, detail)
                }
            )
            if ((result.exitCode == -2 || result.exitCode == -3) && attempt < 3) {
                report(step, ctx.getString(R.string.container_auto_retry, attempt))
                delay(2_000L)
                continue
            }
            break
        }

        // Android 上 SIGCHLD 常为 SIG_IGN，子进程被系统自动回收，退出码恒为 -1；
        // 而且进程结束得太快时 PTY 的最后一行也可能读不到。
        // 因此以磁盘上的 .state 为唯一权威判据。
        val doneBefore = readState(optDir)
        // 只有「必需步骤」决定容器可用；可选组件（node/go/rust）失败不再算失败。
        val requiredSteps = BootstrapScript.requiredSteps(cfg.distro)
        val optionalSteps = BootstrapScript.optionalSteps(cfg.distro)
        val failedNow = BootstrapScript.failedSteps(result.output)
        val requiredFailedNow = failedNow.any { it in requiredSteps }
        // CA 只需要基础包（1/2）完成；Node/Rust/Go 失败不应阻止补 CA。
        val baseDone = setOf(1, 2).all { it in doneBefore }
        Log.i(
            "IT",
            "total=$total required=$requiredSteps optional=$optionalSteps"
        )

        // 第 2 步 apt/apk 装 ca-certificates 会把骨架阶段 seed 的聚合文件整个覆盖掉，
        // 必须装包成功后补回一次；顺带给该容器生成专属 CA（供需要自签信任的组件用）。
        if (baseDone && !requiredFailedNow) {
            runCatching {
                ProotLauncher.seedCaBundle(ctx, rootfs, force = true)
                ProotLauncher.ensureContainerCa(ctx, rootfs, uuid)
            }.onFailure { Log.w("IT", "ca reseed failed: ${it.message}") }
        }

        val doneSteps = readState(optDir)
        val missingRequired = requiredSteps.filter { it !in doneSteps }
        val requiredFailed = requiredFailedNow || missingRequired.isNotEmpty()
        val missingOptional = optionalSteps.filter { it !in doneSteps }
        // 只有必需步骤全部成功才算可用。可选步骤缺失/失败写成
        // `optional failed: 4/5` 这类 warning，不再把容器钉死在 initialized=false
        // （实测 alpine 可选 go/rust 没装完就永远停在 4/5）。
        val finished = !requiredFailed
        // 初始化会写入大量软件包，旧的体积缓存立即作废；下次打开容器列表重新统计。
        runCatching { com.zerolinux.app.core.SysInfo.invalidate(rootfs) }
        val optionalWarn = buildList {
            missingOptional.forEach { add("$it/$total") }
            if (isEmpty()) BootstrapScript.optionalFailedTags(result.output).forEach { add(it) }
        }.joinToString(",")

        val latest = repo.find(uuid)
            ?: throw CancellationException("container deleted during initialize")
        val updated = latest.copy(
            initialized = finished,
            rootfsReady = true,
            initStep = if (finished) "" else "bootstrap:$step/$total",
            lastError = when {
                requiredFailed -> when {
                    // ContainerShell 超时返回 -2，长时间无输出返回 -3；翻译成人话。
                    result.exitCode == -2 -> "bootstrap timeout (60 min), step $step/$total"
                    result.exitCode == -3 -> "bootstrap stalled (no output 15 min), step $step/$total"
                    else -> buildString {
                        append("bootstrap failed: required ")
                        append(
                            (missingRequired + failedNow.filter { it in requiredSteps })
                                .distinct()
                                .joinToString(",") { "$it/$total" }
                        )
                        val tail = failureLogTail(ctx, uuid)
                        if (tail.isNotBlank()) append(" · ").append(tail)
                    }
                }
                optionalWarn.isNotEmpty() -> "optional failed: $optionalWarn"
                else -> ""
            }
        )
        repo.save(updated)
        if (finished) {
            onProgress(Progress(Stage.BOOTSTRAP, BootstrapScript.totalSteps(cfg.distro), BootstrapScript.totalSteps(cfg.distro), "prune"))
            runCatching { pruneRuntimeArtifacts(rootfs) }
        }
        updated
    }

    /**
     * 必需步骤失败时把容器内日志尾部带回 lastError。
     *
     * PRoot 现在把 containers/<uuid>/tmp 绑到 guest /tmp，因此不同容器
     * 的 apt/apk 日志和临时下载文件互相隔离；这里读取同一个目录。
     * uuid 为空时回退到旧的全局 tmp（兼容老调用）。
     */
    private fun failureLogTail(ctx: Context, uuid: String?): String {
        val names = listOf(
            "zl-apt-install.log",
            "zl-apt-update.log",
            "zl-apt-fix.log",
            "zl-dpkg-fix.log",
            "zl-apk-add.log",
            "zl-apk-update.log",
            "zl-apk-fix.log"
        )
        val dir = uuid?.let { File(AppPaths.containerDir(ctx, it), "tmp") }
            ?: AppPaths.tmpDir(ctx)
        return runCatching {
            // 优先把真正的 dpkg 失败包名放进 lastError；日志尾部往往只剩
            // `E: Sub-process /usr/bin/dpkg returned an error code (1)`。
            val dpkgError = File(dir, "zl-apt-install.log")
                .takeIf { it.isFile }
                ?.useLines { lines -> lines.firstOrNull { it.startsWith("dpkg: error processing package") } }
                ?.trim()
                .orEmpty()
            val tail = names.mapNotNull { name ->
                val f = File(dir, name)
                if (!f.isFile) return@mapNotNull null
                val t = f.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .takeLast(4)
                    .joinToString(" | ")
                if (t.isBlank()) null else "$name: $t"
            }.joinToString(" || ")
            listOf(dpkgError, tail).filter { it.isNotBlank() }.joinToString(" || ").take(600)
        }.getOrDefault("")
    }

    /** 每次初始化前清掉该容器上一轮的日志，避免旧错误混进本次结果。 */
    private fun clearGuestLogs(ctx: Context, uuid: String) {
        val dir = File(AppPaths.containerDir(ctx, uuid), "tmp")
        listOf(
            "zl-apt-install.log",
            "zl-apt-update.log",
            "zl-apt-fix.log",
            "zl-dpkg-fix.log",
            "zl-apk-add.log",
            "zl-apk-update.log",
            "zl-apk-fix.log"
        ).forEach { runCatching { File(dir, it).delete() } }
    }

    // ------------------------------------------------------- offline archive

    /**
     * 从用户选中的本地 rootfs 压缩包建容器（首启离线包入口）。
     * 跳过下载与校验，直接解压；其余流程与在线创建一致。
     */
    suspend fun createFromArchive(
        distro: DistroId,
        archive: Uri,
        id: String? = null,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        val cfg = ContainerConfig(
            uuid = UUID.randomUUID().toString(),
            id = id?.trim()?.takeIf { it.isNotEmpty() } ?: repo.suggestId(distro),
            distro = distro,
            abi = abi,
            createdAt = System.currentTimeMillis()
        )
        repo.create(cfg)
        ContainerExternal.dir(ctx, cfg.id, cfg.uuid)

        try {
            onProgress(Progress(Stage.PREPARE, 0, 1, "copy archive"))
            val rootfs = AppPaths.containerRootfs(ctx, cfg.uuid)
            if (rootfs.exists()) rootfs.deleteRecursively()
            rootfs.mkdirs()

            val tmp = File(ctx.cacheDir, "offline-${System.currentTimeMillis()}")
            ctx.contentResolver.openInputStream(archive)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IllegalStateException("无法读取所选文件")

            onProgress(Progress(Stage.EXTRACT, 0, 1, tmp.name))
            RootfsExtractor.extract(tmp, rootfs) { read ->
                onProgress(Progress(Stage.EXTRACT, (read / 1024).toInt(), 0, ""))
            }
            tmp.delete()

            flattenNestedRoot(rootfs)
            ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
            markRootfsComplete(rootfs)

            if (repo.find(cfg.uuid) == null) {
                throw CancellationException("container deleted during rootfs install")
            }
            val done = cfg.copy(rootfsReady = true, lastError = "")
            repo.save(done)
            onProgress(Progress(Stage.EXTRACT, 1, 1, "rootfs ready"))
            done
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (repo.find(cfg.uuid) != null) {
                repo.save(cfg.copy(lastError = t.message ?: t.javaClass.simpleName))
            }
            throw t
        }
    }

    // ----------------------------------------------------------------- clone

    /**
     * 克隆包含容器设置。
     *
     * 刻意不用 `cp -al` 硬链接：apt 之类的工具会原地改写文件内容，
     * 硬链接会让克隆体和源容器互相污染，直接把源容器弄坏。
     * 真实复制慢一些，但结果正确。
     */
    suspend fun clone(
        uuid: String,
        newId: String? = null,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val src = repo.find(uuid) ?: throw IllegalStateException("container not found: $uuid")
        val newUuid = UUID.randomUUID().toString()

        // 目标 ID 必须唯一且合法：在建目录之前就挡掉，
        // 既避免半成品，也绝不静默覆盖已有同名容器。
        val targetId = newId?.trim()?.takeIf { it.isNotEmpty() } ?: repo.suggestId(src.distro)
        if (targetId.length > 40 || targetId.any { it in "/\\:*?\"<>|" || it.isWhitespace() || it.isISOControl() }) {
            throw IllegalStateException("容器 ID 不合法：$targetId")
        }
        repo.findById(targetId)?.let { throw IllegalStateException("容器 ID 已存在：$targetId") }

        val copyCfg = src.copy(
            uuid = newUuid,
            id = targetId,
            createdAt = System.currentTimeMillis(),
            lastError = ""
        )
        repo.create(copyCfg)
        ContainerExternal.dir(ctx, copyCfg.id, copyCfg.uuid)

        try {
            onProgress(Progress(Stage.COPY, 0, 1, "rootfs"))
            val srcRoot = AppPaths.containerRootfs(ctx, uuid)
            val dstRoot = AppPaths.containerRootfs(ctx, newUuid).apply { mkdirs() }
            copyTree(srcRoot, dstRoot) { done, total ->
                onProgress(Progress(Stage.COPY, done.toInt(), total.toInt(), "rootfs"))
            }

            // 克隆目标可能在复制期间被用户删除：不能 repo.save 把已删除的配置加回来。
            if (repo.find(copyCfg.uuid) == null) {
                throw kotlinx.coroutines.CancellationException("clone target deleted")
            }
            repo.save(copyCfg)
            copyCfg
        } catch (t: Throwable) {
            onProgress(Progress(Stage.COPY, 0, 1, "rollback"))
            ContainerExternal.remove(ctx, copyCfg.id, newUuid)
            AppPaths.containerDir(ctx, newUuid).deleteRecursively()
            File(AppPaths.l2sDir(ctx), newUuid).deleteRecursively()
            repo.delete(newUuid)
            throw t
        }
    }

    // ---------------------------------------------------------------- restart

    /**
     * 重启容器：停掉该 rootfs 下所有 PRoot 进程（含 opencode serve 与终端 shell），
     * 清掉会话注册表后重新拉起可用的 shell 与 AI 服务。
     *
     * 不删除 rootfs、不重置 initialized；只是把「进程」这一层重新做一遍。
     * 这是用户在设置里点「重启容器」的落点，比删除后重建快得多。
     */
    suspend fun restart(
        uuid: String,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val cfg = repo.find(uuid) ?: throw IllegalStateException("container not found: $uuid")
        val rootfs = AppPaths.containerRootfs(ctx, uuid)
        if (!rootfs.isDirectory) throw IllegalStateException("rootfs missing: $uuid")

        onProgress(Progress(Stage.PREPARE, 0, 3, "stop ai"))
        // 先杀掉该 rootfs 下所有 PRoot 进程（opencode serve 与终端 shell）。
        // AI 服务的 ContainerShell 协程在 :ai 进程里，跨进程无法直接取消；
        // 进程消失后其读取循环会自然结束，随后 restart=true 会再清理一次。
        onProgress(Progress(Stage.PREPARE, 1, 3, "stop proot"))
        val killed = killProotFor(rootfs)
        Log.i(TAG, "restart '$uuid': killed $killed proot process(es)")

        onProgress(Progress(Stage.PREPARE, 2, 3, "resume"))
        ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)
        runCatching { ProotLauncher.seedCaBundle(ctx, rootfs) }

        // 终端会话在 :terminal 进程，不能直接调 SessionRegistry。
        // 等 rootfs 骨架/证书写完再落信号，避免终端进程抢先重建 shell 时
        // 和主进程同时写同一批文件。
        runCatching { SessionSignals.requestRestart(ctx, uuid) }
        // 重新拉起 AI；密码/端口沿用容器配置。服务在 :ai 进程，
        // restart=true 会让它清掉旧 job 并重新 seed AAR 的服务器条目。
        runCatching { AiIntents.startServerFor(ctx, uuid, restart = true) }
            .onFailure { Log.w(TAG, "restart opencode failed: $uuid", it) }
        onProgress(Progress(Stage.PREPARE, 3, 3, "ready"))
        cfg
    }

    /** 找出命令行里带这个 rootfs 的 PRoot 主进程（`libproot.so -r <rootfs>`）。 */
    private fun prootPidsFor(rootfs: File): Set<Int> {
        val needle = rootfs.absolutePath
        val out = HashSet<Int>()
        val self = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            val raw = runCatching { File(dir, "cmdline").readBytes() }.getOrNull()
                ?: return@forEach
            if (raw.isEmpty()) return@forEach
            val text = String(raw, Charsets.UTF_8)
            // 只认真 PRoot：防止误杀任何恰好带这个路径的普通进程。
            if (!text.contains(needle) || !text.contains("proot")) return@forEach
            out += pid
        }
        return out
    }

    private fun childrenOf(ppid: Int): List<Int> {
        val out = ArrayList<Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull()
                ?: return@forEach
            // stat: pid (comm) state ppid ...；comm 里可能有空格，从最后一个 ')' 之后切。
            val rest = stat.substringAfterLast(')').trim()
            val parent = rest.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                ?: return@forEach
            if (parent == ppid) out += pid
        }
        return out
    }

    private fun collectDescendants(root: Int, out: MutableSet<Int>) {
        for (child in childrenOf(root)) {
            if (out.add(child)) collectDescendants(child, out)
        }
    }

    /**
     * 杀掉 rootfs 对应的 PRoot 及其全部后代。最多重试 4 秒，覆盖
     * 「父进程先死、子进程短暂残留」以及新 fork 出来的 guest 子进程。
     */
    private fun killProotFor(rootfs: File): Int {
        var killed = 0
        val deadline = System.currentTimeMillis() + 4_000L
        while (true) {
            val roots = prootPidsFor(rootfs)
            if (roots.isEmpty()) break
            val all = LinkedHashSet<Int>()
            roots.forEach {
                all += it
                collectDescendants(it, all)
            }
            // 先杀后代再杀 tracer：tracer 先死会让 /proc 里的子进程数变化，
            // 一次性按集合杀完最省事。Os.kill 对已消失的 pid 返回 ESRCH，忽略即可。
            all.sortedDescending().forEach { pid ->
                runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                killed++
            }
            Thread.sleep(120)
            if (System.currentTimeMillis() >= deadline) break
        }
        return killed
    }

    // ---------------------------------------------------------------- delete

    fun delete(uuid: String) {
        // 先取 id：repo.delete 之后就查不到配置了。外接目录只删 containers/ 下的子目录。
        val cfg = repo.find(uuid)
        // rootfs 下的 PRoot 必须先杀掉，否则删除目录时仍有进程占用；
        // 终端会话在 :terminal 进程，通过信号文件让它移除对应会话。
        runCatching { killProotFor(AppPaths.containerRootfs(ctx, uuid)) }
        runCatching { SessionSignals.requestRemove(ctx, uuid) }
        ContainerExternal.remove(ctx, cfg?.id, uuid)
        AppPaths.containerDir(ctx, uuid).deleteRecursively()
        File(AppPaths.l2sDir(ctx), uuid).deleteRecursively()
        repo.delete(uuid)
    }

    // ---------------------------------------------------------------- backup

    /** 导出一个 .zlbak（tar.gz）到用户选的 SAF Uri，全程流式，不落临时文件。 */
    suspend fun backup(
        uuid: String,
        target: Uri,
        onProgress: (Progress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val cfg = repo.find(uuid) ?: throw IllegalStateException("container not found: $uuid")
        val srcRoot = AppPaths.containerRootfs(ctx, uuid)

        val files = ArrayList<File>()
        collectFiles(srcRoot, files, atRoot = true)
        val total = files.size

        val out = ctx.contentResolver.openOutputStream(target)
            ?: throw IllegalStateException("cannot open output stream")

        out.use { raw ->
            GzipCompressorOutputStream(raw.buffered()).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                    val meta = JSONObject()
                        .put("format", "zerolinux-backup")
                        .put("version", 2)
                        .put("createdAt", System.currentTimeMillis())
                        .put("id", cfg.id)
                        .put("distro", cfg.distro.key)
                        .put("uuid", cfg.uuid)
                    val metaBytes = meta.toString(2).toByteArray()
                    tar.putArchiveEntry(
                        TarArchiveEntry("meta/backup.json").apply { size = metaBytes.size.toLong() }
                    )
                    tar.write(metaBytes)
                    tar.closeArchiveEntry()

                    val sidecar = AppPaths.containerJson(ctx, uuid)
                    if (sidecar.exists()) addFile(tar, sidecar, "container.json")

                    files.forEachIndexed { index, file ->
                        val rel = "rootfs/" + file.relativeTo(srcRoot).invariantSeparatorsPath
                        addFile(tar, file, rel)
                        if (index % 64 == 0) {
                            onProgress(Progress(Stage.ARCHIVE, index, total, rel))
                        }
                    }
                    tar.finish()
                }
            }
        }
        onProgress(Progress(Stage.ARCHIVE, total, total, "done"))
    }

    // --------------------------------------------------------------- restore

    /** 从 .zlbak 导入成一个新容器：新 uuid、新端口、ID 冲突自动加后缀。 */
    suspend fun restore(
        source: Uri,
        onProgress: (Progress) -> Unit = {}
    ): ContainerConfig = withContext(Dispatchers.IO) {
        val tmp = File(AppPaths.tmpDir(ctx), "restore-${System.currentTimeMillis()}")
            .apply { mkdirs() }
        try {
            onProgress(Progress(Stage.RESTORE, 0, 1, "copy"))
            val archive = File(tmp, "backup.zlbak")
            ctx.contentResolver.openInputStream(source)?.use { input ->
                archive.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IllegalStateException("cannot open input stream")

            onProgress(Progress(Stage.RESTORE, 0, 1, "extract"))
            val unpack = File(tmp, "unpack").apply { mkdirs() }
            RootfsExtractor.extract(archive, unpack) { }
            archive.delete()

            onProgress(Progress(Stage.RESTORE, 0, 1, "meta"))
            val metaFile = File(unpack, "meta/backup.json")
            if (!metaFile.isFile) throw IllegalStateException("not a ZeroLinux backup")
            val meta = JSONObject(metaFile.readText())
            if (meta.optString("format") != "zerolinux-backup") {
                throw IllegalStateException("unsupported backup format")
            }

            val sidecarFile = File(unpack, "container.json")
            if (!sidecarFile.isFile) throw IllegalStateException("backup has no container.json")
            val saved = ContainerConfig.fromJson(JSONObject(sidecarFile.readText()))

            val newUuid = UUID.randomUUID().toString()
            val cfg = saved.copy(
                uuid = newUuid,
                id = uniqueId(saved.id),
                createdAt = System.currentTimeMillis(),
                lastError = ""
            )

            val rootfs = AppPaths.containerRootfs(ctx, newUuid).apply { mkdirs() }
            copyTree(File(unpack, "rootfs"), rootfs) { done, total ->
                onProgress(Progress(Stage.RESTORE, done.toInt(), total.toInt(), "rootfs"))
            }

            repo.create(cfg)
            ContainerExternal.dir(ctx, cfg.id, cfg.uuid)
            ProotLauncher.ensureRootfsSkeleton(ctx, rootfs)

            // 恢复出来的是已经初始化过的文件系统，但环境可能变了（ABI / 初始化状态），
            // 保留 initialized，让用户自己决定是否重建。
            onProgress(Progress(Stage.RESTORE, 1, 1, "done"))
            cfg
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * 初始化完成后清掉运行时垃圾：PRoot 自动创建的 bind 挂载点骨架、
     * npm 缓存、包管理器缓存与临时目录。这些都能随时重建，留着只会让
     * 克隆和备份白白多出几百 MB。
     */
    fun pruneRuntimeArtifacts(rootfs: File) {
        // bind 骨架：guest 运行时会重新 bind，不必持久化
        listOf("data", "storage", "sdcard", "external", "mnt/sdcard").forEach { rel ->
            runCatching { File(rootfs, rel).deleteRecursively() }
        }
        listOf(
            "root/.npm/_cacache",
            "root/.npm/_logs",
            "root/.cache",
            "var/cache/apk",
            "var/cache/apt/archives",
            "var/lib/apt/lists",
            "var/cache/yum"
        ).forEach { rel ->
            runCatching { File(rootfs, rel).deleteRecursively() }
        }
        // tmp 仍要存在，guest 里有程序直接往里写
        runCatching { File(rootfs, "tmp").mkdirs() }
    }

    private fun readState(optDir: File): Set<Int> = runCatching {
        File(optDir, ".state").readLines()
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun appendState(optDir: File, step: Int) {
        runCatching {
            optDir.mkdirs()
            File(optDir, ".state").appendText("$step\n")
        }
    }

    private fun uniqueId(base: String): String {
        if (repo.findById(base) == null) return base
        var n = 2
        while (repo.findById("$base-$n") != null) n++
        return "$base-$n"
    }

    /**
     * 写入一个 tar 条目。
     *
     * PROOT_LINK2SYMLINK 会把大文件搬进全局 l2s 并以绝对符号链接指回去，
     * 这样的备份无法自包含。遇到这种链接就沿链找到 l2s 里的真文件，按普通文件写入。
     */
    private fun addFile(tar: TarArchiveOutputStream, file: File, name: String) {
        val link = if (isSymlink(file)) {
            runCatching {
                java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
            }.getOrNull()
        } else {
            null
        }
        val expanded = if (link != null) l2sRealFile(file) else null
        val payload = expanded ?: file

        val entry = if (link != null && expanded == null) {
            // 注意：TarArchiveEntry(name, true) 的第二个参数是 preserveAbsolutePath，
            // 不是「符号链接」标志；必须显式用 LF_SYMLINK，否则 tar 里会变成一个
            // 0 字节普通文件，恢复后所有软链全部失效。
            TarArchiveEntry(
                name,
                org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK
            ).apply { linkName = link }
        } else if (payload.isDirectory) {
            // 目录也要显式入档，否则恢复出来的目录一律是 mkdirs() 的 0700
            TarArchiveEntry(if (name.endsWith("/")) name else "$name/").apply {
                mode = 0x1ED
            }
        } else {
            TarArchiveEntry(name).apply { size = payload.length() }
        }
        entry.mode = when {
            payload.isDirectory -> 0x1ED   // 0755
            payload.canExecute() -> 0x1ED
            else -> 0x1A4                  // 0644
        }

        tar.putArchiveEntry(entry)
        // 只有「真文件」才写内容。注意 payload.isFile 会跟随符号链接，
        // 所以必须用 link/expanded 判断，否则会把目标的字节灌进 size=0 的
        // 软链条目，抛 "write exceeds size in header"。
        if ((link == null || expanded != null) && payload.isFile) {
            payload.inputStream().use { it.copyTo(tar, 128 * 1024) }
        }
        tar.closeArchiveEntry()
    }

    private fun collectFiles(dir: File, out: MutableList<File>, atRoot: Boolean = false) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name
            if (name == "cache" || name == "tmp" || name == ".zl") continue
            // bind 挂载点骨架（尤其 rootfs/data/.../l2s 权限 0000）不备份
            if (atRoot && name in BIND_ARTIFACTS) continue
            val link = isSymlink(child)
            // 符号链接必须无条件收集：guest 内绝对路径（如 /bin/sh -> /bin/busybox）
            // 在宿主命名空间里是悬空的，canRead() 会是 false，但链接本身必须保住。
            if (!link && !child.canRead()) continue
            if (child.isDirectory && !link) {
                out.add(child)
                collectFiles(child, out)
            } else {
                out.add(child)
            }
        }
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)

    /**
     * 有些发行版的 tarball 会把整棵 rootfs 套在一层目录里（Kali NetHunter full
     * 就是 `kali-arm64/`），直接解压会导致 guest 里根本没有 /bin/sh。检测到
     * 「rootfs 下只有一个目录，且它自己看起来就是个根」时，把这层搬平。
     */
    private fun flattenNestedRoot(rootfs: File) {
        val children = rootfs.listFiles() ?: return
        if (children.size != 1) return
        val only = children.first()
        if (!only.isDirectory || isSymlink(only)) return
        val looksLikeRoot = File(only, "etc/os-release").isFile ||
            File(only, "bin").exists() ||
            File(only, "usr/bin").exists()
        if (!looksLikeRoot) return

        only.listFiles()?.forEach { child ->
            val dest = File(rootfs, child.name)
            if (dest.exists()) return@forEach
            runCatching { child.renameTo(dest) }
        }
        runCatching { only.delete() }
    }

    /** 若 [file] 是指向 l2s 存储的符号链接，返回其中的真实文件；否则返回 null。 */
    private fun l2sRealFile(file: File): File? {
        val l2sRoot = AppPaths.l2sDir(ctx).absolutePath
        var cur: File = file

        repeat(8) {
            if (!isSymlink(cur)) {
                return cur.takeIf {
                    it.isFile && it.absolutePath.startsWith(l2sRoot)
                }
            }
            val link = runCatching {
                java.nio.file.Files.readSymbolicLink(cur.toPath())
            }.getOrNull() ?: return null
            cur = if (link.isAbsolute) link.toFile() else File(cur.parentFile, link.toString())
        }
        return null
    }

    private suspend fun copyTree(src: File, dst: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        if (!src.exists()) return
        val stack = ArrayDeque<Pair<File, File>>()
        stack.addLast(src to dst)
        var count = 0L

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (from, to) = stack.removeLast()
            val atRoot = from.absolutePath == src.absolutePath
            val children = from.listFiles()
            if (children == null) {
                copyOne(from, to)
                count++
                if (count % 256 == 0L) onProgress(count, 0)
                continue
            }
            if (!to.exists()) to.mkdirs()
            for (child in children) {
                // PRoot 会在 rootfs 里自动创建 bind 挂载点骨架，其中
                // rootfs/data/.../l2s 这类目录权限是 0000，读它直接 EACCES。
                // 这些目录在 guest 里运行时会被真实 bind 覆盖，不需要复制。
                if (atRoot && child.name in BIND_ARTIFACTS) continue
                val link = isSymlink(child)
                if (!link && !child.canRead()) continue
                val target = File(to, child.name)
                if (child.isDirectory && !link) {
                    target.mkdirs()
                    copyPermissions(child, target)
                    stack.addLast(child to target)
                } else {
                    runCatching { copyOne(child, target) }
                    count++
                    if (count % 256 == 0L) onProgress(count, 0)
                }
            }
        }
        onProgress(count, count)
    }

    private fun copyOne(from: File, to: File) {
        if (isSymlink(from)) {
            // 先尝试把 l2s 里的真实数据复制过来，让克隆结果自包含
            val expanded = l2sRealFile(from)
            if (expanded != null) {
                expanded.inputStream().use { input ->
                    to.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
                copyPermissions(expanded, to)
                return
            }
            val link = runCatching {
                java.nio.file.Files.readSymbolicLink(from.toPath()).toString()
            }.getOrNull()
            if (link != null) {
                runCatching { java.nio.file.Files.deleteIfExists(to.toPath()) }
                runCatching {
                    java.nio.file.Files.createSymbolicLink(to.toPath(), File(link).toPath())
                }
                return
            }
        }
        from.inputStream().use { input ->
            to.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
        copyPermissions(from, to)
    }

    /** 逐文件复制会丢掉权限位（曾把 0755 变成 0711），这里显式照抄。 */
    private fun copyPermissions(from: File, to: File) {
        runCatching {
            val perms = java.nio.file.Files.getPosixFilePermissions(
                from.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS
            )
            java.nio.file.Files.setPosixFilePermissions(to.toPath(), perms)
        }
    }

    companion object {
        /** PRoot 为 bind mount 在 rootfs 内创建的骨架目录，一律不复制/不备份。 */
        private val BIND_ARTIFACTS = listOf("data", "storage", "sdcard", "external")

        fun stamp(): String =
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    }
}
