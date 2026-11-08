package com.zerolinux.app.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.zerolinux.app.container.ContainerRepo
import com.zerolinux.app.core.GlobalSettings
import org.json.JSONArray

/**
 * 容器元数据的唯一写者，常驻 :main 进程。
 * `:terminal` 进程只通过这里读容器配置。
 */
class ContainerService : Service() {

    private val repo by lazy { ContainerRepo(this) }

    private val binder = object : IContainerService.Stub() {
        override fun getApiVersion(): String = API_VERSION

        override fun getContainerCount(): Int = runCatching { repo.all().size }.getOrDefault(0)

        override fun getActiveDistro(): String {
            val active = runCatching { repo.active() }.getOrNull()
            return active?.distro?.key
                ?: GlobalSettings.load(this@ContainerService).selectedDistro
        }

        override fun listContainersJson(): String = runCatching {
            val arr = JSONArray()
            repo.all().forEach { arr.put(it.toJson()) }
            arr.toString()
        }.getOrDefault("[]")

        override fun getActiveContainerJson(): String = runCatching {
            repo.active()?.toJson()?.toString().orEmpty()
        }.getOrDefault("")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val API_VERSION = "1"
        const val ACTION = "com.zerolinux.app.IContainerService"
    }
}
