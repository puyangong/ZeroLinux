package com.zerolinux.app.engine

import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PTY 原生层。符号名必须与 app/src/main/cpp/pty.cpp 中
 * Java_com_zerolinux_app_engine_PtyNative_* 一一对应。
 */
object PtyNative {

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = runCatching { System.loadLibrary("pty") }.isSuccess
        return loaded
    }

    external fun createSubprocess(
        cmdPath: String,
        args: Array<String>,
        env: Array<String>?,
        cwdPath: String?,
        cols: Int,
        rows: Int,
        outPid: IntArray
    ): Int

    external fun setPtyWindowSize(masterFd: Int, cols: Int, rows: Int)

    external fun closeFd(masterFd: Int)

    external fun waitForProcess(pid: Int): Int
}

class PtyProcess(val masterFdInt: Int, val pid: Int) {

    val parcelFd: ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(masterFdInt)
    val inputStream: FileInputStream = FileInputStream(parcelFd.fileDescriptor)
    val outputStream: FileOutputStream = FileOutputStream(parcelFd.fileDescriptor)

    fun updateWindowSize(cols: Int, rows: Int) {
        if (masterFdInt >= 0) PtyNative.setPtyWindowSize(masterFdInt, cols, rows)
    }

    fun destroy() {
        runCatching { android.os.Process.killProcess(pid) }
        runCatching { parcelFd.close() }
    }

    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
}
