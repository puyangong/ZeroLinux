package com.zerolinux.app.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/** 跨进程绑定 :main 里的 ContainerService。回调统一切回主线程。 */
class ContainerBinding(private val context: Context) {

    var service: IContainerService? = null
        private set

    var connected: Boolean = false
        private set

    private var connection: ServiceConnection? = null

    fun bind(onChanged: () -> Unit) {
        if (connection != null) return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IContainerService.Stub.asInterface(binder)
                connected = service != null
                onChanged()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                connected = false
                onChanged()
            }
        }
        connection = conn
        val ok = runCatching {
            context.bindService(Intent(context, ContainerService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            connection = null
            connected = false
        }
    }

    fun unbind() {
        connection?.let { conn -> runCatching { context.unbindService(conn) } }
        connection = null
        service = null
        connected = false
    }
}
