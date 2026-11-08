package com.zerolinux.app.engine

import android.content.Context
import android.os.Build
import com.zerolinux.app.core.AppPaths
import java.io.File

data class NativeReport(
    val abi: String,
    val present: List<String>,
    val missing: List<String>,
    val prootVersion: String?,
    val ptyLoaded: Boolean,
    val error: String?
) {
    val libsOk: Boolean get() = missing.isEmpty()
    val prootOk: Boolean get() = prootVersion != null
    val ok: Boolean get() = libsOk && ptyLoaded && prootOk
}

/**
 * 第 1 步的原生层冒烟：确认 useLegacyPackaging 生效（.so 是真文件）、
 * 确认 libproot 能在设备上真正跑起来，而不是仅仅存在于 APK 里。
 */
object NativeProbe {

    private val REQUIRED = listOf(
        "libproot.so",
        "libproot_loader.so",
        "libproot_loader32.so",
        "libtalloc.so",
        "libandroid-shmem.so",
        "libpty.so"
    )

    fun run(ctx: Context): NativeReport {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val present = ArrayList<String>(REQUIRED.size)
        val missing = ArrayList<String>(0)

        for (name in REQUIRED) {
            val f = File(dir, name)
            if (f.exists() && f.length() > 0L) present += name else missing += name
        }

        val ptyLoaded = runCatching { PtyNative.ensureLoaded() }.getOrDefault(false)

        var version: String? = null
        var error: String? = null
        val proot = File(dir, "libproot.so")
        if (proot.exists()) {
            version = runCatching { execVersion(proot, ctx) }.getOrElse {
                error = it.message ?: it.javaClass.simpleName
                null
            }
        } else {
            error = "libproot.so missing"
        }

        return NativeReport(
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            present = present,
            missing = missing,
            prootVersion = version,
            ptyLoaded = ptyLoaded,
            error = error
        )
    }

    private fun execVersion(proot: File, ctx: Context): String {
        // useLegacyPackaging 解出来的 so 通常是 644，先补执行位
        if (!proot.canExecute()) runCatching { proot.setExecutable(true, false) }

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val pb = ProcessBuilder(proot.absolutePath, "--version").redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", nativeDir)
            put("PROOT_TMP_DIR", AppPaths.tmpDir(ctx).absolutePath)
            put("PROOT_NO_SECCOMP", "1")
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        p.waitFor()
        // proot --version 先打印一段 ASCII banner，版本号在 banner 最后一行，
        // 所以不能直接取第一行非空文本（那会得到 " _____ _____ ..."）。
        return VERSION_RE.find(out)?.value
            ?: out.lineSequence().firstOrNull { it.isNotBlank() }
            ?: "(no output)"
    }

    private val VERSION_RE = Regex("""\d+\.\d+[0-9A-Za-z.\-+]*""")
}
