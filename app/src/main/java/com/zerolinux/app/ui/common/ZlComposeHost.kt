package com.zerolinux.app.ui.common

import android.app.Activity
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 给「非 ComponentActivity」的窗口临时搭一套 Compose owner。
 *
 * AAR 的 `com.opencode.mobile.MainActivity` 直接继承 `android.app.Activity`，
 * 没有 lifecycle / savedStateRegistry / viewModelStore。宿主要在它上面用
 * `ComposeView` 渲染统一的 `ZlTopBar`，只能自己补这三个 owner：
 * Compose 会沿 View 树向上查找，把它们装在 ComposeView 自己身上即可。
 *
 * 只服务于一条顶栏，所以状态简单：attach 时直接 RESUMED，宿主 Activity
 * 销毁时 destroy。不要在别处复用这个类去做完整页面级 Compose 宿主。
 */
class ZlHostOwners(private val activity: Activity) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    /** 必须主线程调用；重复调用无副作用（Activity 会多次 resume）。 */
    fun start() {
        if (registry.currentState == Lifecycle.State.RESUMED) return
        runCatching {
            saved.performAttach()
            saved.performRestore(null)
        }
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** 装到 ComposeView 上：Compose 从这里向上找到所需 owner。 */
    fun install(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    /** Activity 销毁时调用，避免 recomposer 泄漏。 */
    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /** 未使用，仅用于日志排查时区分不同 Activity。 */
    fun hostName(): String = activity.javaClass.simpleName
}
