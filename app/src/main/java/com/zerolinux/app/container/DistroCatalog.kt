package com.zerolinux.app.container

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.zerolinux.app.R
import com.zerolinux.app.ui.theme.ZlPinned
import com.zerolinux.app.ui.theme.ZlRunning
import com.zerolinux.app.ui.theme.ZlUnread
import com.zerolinux.app.ui.theme.ZlPending

enum class DistroId {
    UBUNTU, ALPINE, KALI, DEBIAN;

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(value: String?): DistroId =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: UBUNTU
    }
}

data class DistroOption(
    val id: DistroId,
    val key: String,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    val accent: Color
)

/**
 * 四种发行版。rootfs URL / 体积 / 包管理器在第 2 步补进来，
 * 这里先固定 UI 侧的元数据，避免界面改动。
 */
object DistroCatalog {

    val all: List<DistroOption> = listOf(
        DistroOption(DistroId.UBUNTU, "ubuntu", R.string.distro_ubuntu, R.string.distro_ubuntu_desc, ZlUnread),
        DistroOption(DistroId.ALPINE, "alpine", R.string.distro_alpine, R.string.distro_alpine_desc, ZlPinned),
        DistroOption(DistroId.KALI, "kali", R.string.distro_kali, R.string.distro_kali_desc, ZlPending),
        DistroOption(DistroId.DEBIAN, "debian", R.string.distro_debian, R.string.distro_debian_desc, ZlRunning)
    )

    fun byKey(key: String): DistroOption? = all.firstOrNull { it.key == key }
}
