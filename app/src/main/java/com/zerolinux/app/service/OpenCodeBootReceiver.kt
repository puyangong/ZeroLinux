package com.zerolinux.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机、应用升级、用户解锁后恢复 active 容器的 OpenCode 服务。
 * 失败（例如 Android 后台 FGS 限制）不崩溃，等下次打开 App 再拉起。
 */
class OpenCodeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED -> OpenCodeServerService.start(context)
        }
    }
}
