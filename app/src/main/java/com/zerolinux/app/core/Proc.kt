package com.zerolinux.app.core

import android.app.Application
import android.os.Build
import java.io.File

object Proc {

    fun name(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { Application.getProcessName() }.getOrDefault("unknown")
    } else {
        runCatching { File("/proc/self/cmdline").readText().trim('\u0000', '\n', ' ') }
            .getOrDefault("unknown")
    }

    /** ":terminal" 这种短标签，用于空间紧张的顶栏副标题。 */
    fun shortName(): String {
        val full = name()
        return if (full.contains(':')) full.substringAfter(':').let { ":$it" } else full
    }

    fun pid(): Int = android.os.Process.myPid()
}
