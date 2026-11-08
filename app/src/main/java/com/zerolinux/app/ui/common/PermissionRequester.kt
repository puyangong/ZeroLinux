package com.zerolinux.app.ui.common

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.zerolinux.app.core.PermissionsHelper

/**
 * 首启引导与设置页共用的权限请求器。
 * 必须在 Activity 构造阶段创建（registerForActivityResult 的生命周期约束）。
 */
class PermissionRequester(private val activity: ComponentActivity) {

    private var onChange: () -> Unit = {}

    private val notificationLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { onChange() }

    private val storageLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { onChange() }

    fun observe(block: () -> Unit) {
        onChange = block
    }

    fun requestStorage() {
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= Build.VERSION_CODES.R -> {
                if (!PermissionsHelper.openAllFilesAccess(activity)) {
                    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                        storageLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }
            else -> storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionsHelper.notificationGranted(activity)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PermissionsHelper.openNotificationSettings(activity)
        }
    }

    fun requestBackground() {
        if (!PermissionsHelper.requestIgnoreBatteryOptimizations(activity)) {
            if (!PermissionsHelper.openOemAutoStart(activity)) {
                PermissionsHelper.openAppSettings(activity)
            }
        }
    }
}
