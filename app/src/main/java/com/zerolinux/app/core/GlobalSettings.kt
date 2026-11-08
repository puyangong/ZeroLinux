package com.zerolinux.app.core

import android.content.Context
import com.zerolinux.app.i18n.LocaleManager
import org.json.JSONObject

/**
 * 全局设置。每个进程各持一份内存缓存，写入发生在 :main。
 * 跨进程一致性由「重新进入页面时重读」保证（第 1 步够用）。
 */
data class GlobalSettings(
    val language: String = LocaleManager.SYSTEM,
    val theme: String = THEME_DARK,
    val wizardDone: Boolean = false,
    /**
     * 引导进度。仅在 [wizardDone] = false 时有效，用于进程被杀后从上次步骤继续。
     * 每次换步由 WizardActivity 写回，完成后重置为 0。
     */
    val wizardStep: Int = 0,
    val drawerGuideSeen: Boolean = false,
    val selectedDistro: String = "",
    val mirrorMode: String = MIRROR_DOMESTIC,
    val mirrorCustomUrl: String = "",
    val terminalRenderer: String = "web",
    val fontScale: Float = 1.0f,
    /**
     * 界面字号（只作用于 Compose 原生 UI：终端页/容器页/设置页/抽屉）。
     * WebView 终端自带样式，天然不受影响。
     */
    val uiScale: Float = 1.0f,
    val haptics: Boolean = true
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("language", language)
        put("theme", theme)
        put("wizardDone", wizardDone)
        put("wizardStep", wizardStep)
        put("drawerGuideSeen", drawerGuideSeen)
        put("selectedDistro", selectedDistro)
        put("mirrorMode", mirrorMode)
        put("mirrorCustomUrl", mirrorCustomUrl)
        put("terminalRenderer", terminalRenderer)
        put("fontScale", fontScale.toDouble())
        put("uiScale", uiScale.toDouble())
        put("haptics", haptics)
    }

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val MIRROR_DOMESTIC = "domestic"
        const val MIRROR_OVERSEAS = "overseas"
        const val MIRROR_CUSTOM = "custom"
        const val RENDERER_WEB = "web"
        const val RENDERER_NATIVE = "native"

        /**
         * 界面字号的可观察镜像。Compose 主题直接读它，
         * 因此设置页拖动 Slider 时无需落盘即可实时生效。
         */
        val uiScaleState = androidx.compose.runtime.mutableStateOf(1.0f)

        @Volatile
        private var cache: GlobalSettings? = null

        fun load(ctx: Context): GlobalSettings {
            val loaded = cache ?: synchronized(this) {
                cache ?: fromJson(JsonStore.read(AppPaths.globalJson(ctx))).also { cache = it }
            }
            // 同值赋值不会触发重组，load 被频繁调用是安全的。
            uiScaleState.value = loaded.uiScale
            return loaded
        }

        fun save(ctx: Context, settings: GlobalSettings) {
            synchronized(this) {
                JsonStore.write(AppPaths.globalJson(ctx), settings.toJson())
                cache = settings
                uiScaleState.value = settings.uiScale
            }
        }

        fun update(ctx: Context, block: (GlobalSettings) -> GlobalSettings): GlobalSettings {
            val next = block(load(ctx))
            save(ctx, next)
            return next
        }

        fun fromJson(json: JSONObject): GlobalSettings = GlobalSettings(
            language = json.optString("language", LocaleManager.SYSTEM),
            theme = json.optString("theme", THEME_DARK),
            wizardDone = json.optBoolean("wizardDone", false),
            wizardStep = json.optInt("wizardStep", 0).coerceIn(0, 10),
            drawerGuideSeen = json.optBoolean("drawerGuideSeen", false),
            selectedDistro = json.optString("selectedDistro", ""),
            mirrorMode = json.optString("mirrorMode", MIRROR_DOMESTIC),
            mirrorCustomUrl = json.optString("mirrorCustomUrl", ""),
            terminalRenderer = json.optString("terminalRenderer", "web"),
            fontScale = json.optDouble("fontScale", 1.0).toFloat().coerceIn(0.85f, 1.8f),
            uiScale = json.optDouble("uiScale", 1.0).toFloat().coerceIn(0.85f, 1.4f),
            haptics = json.optBoolean("haptics", true)
        )

        /** 仅供进程内测试/切换语言后重置缓存。 */
        fun invalidate() {
            synchronized(this) { cache = null }
        }
    }
}
