package com.zerolinux.app.ui.container

import android.content.Context
import com.zerolinux.app.container.ContainerTasks
import com.zerolinux.app.container.DistroId
import com.zerolinux.app.ui.files.invalidateContainerRoot

/**
 * 引导页与容器管理页共用的启动动作。
 *
 * 创建 / 初始化本身仍走 [ContainerTasks] 的串行队列（下载、解压、装包同一条队），
 * 这里只负责把「建实例 → 初始化 → 刷新文件抽屉」串起来，
 * 避免 [com.zerolinux.app.ui.main.WizardActivity] 里再复制一份重活逻辑。
 */
object ContainerLauncher {

    /**
     * 引导第 3 步确认发行版后调用：创建实例、下载 rootfs，并继续自动初始化。
     * 用户不需要再进设置页点一次；如果初始化中途失败，设置页仍可重试。
     */
    fun create(ctx: Context, distro: DistroId, id: String) {
        ContainerTasks.create(ctx.applicationContext, distro, id, initialize = true)
    }

    /** 初始化做完后作废文件抽屉缓存，并通知 DocumentsProvider 重拉根目录。 */
    fun refreshFiles(ctx: Context, uuid: String) {
        invalidateContainerRoot(ctx, uuid)
    }
}
