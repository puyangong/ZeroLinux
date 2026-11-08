package com.zerolinux.app.i18n

import android.content.Context
import android.content.res.Configuration
import com.zerolinux.app.core.GlobalSettings
import java.util.Locale

/**
 * 语言切换不依赖 AppCompat：直接 createConfigurationContext + recreate()。
 * 支持 system / zh / en 三态，写入 global.json 后对所有进程生效于下一次读取。
 */
object LocaleManager {

    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    fun current(ctx: Context): String = runCatching { GlobalSettings.load(ctx).language }.getOrDefault(SYSTEM)

    fun localeOf(language: String): Locale? = when (language) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
        else -> null
    }

    fun tagOf(language: String): String = when (language) {
        ZH -> "zh"
        EN -> "en"
        else -> "en"
    }

    fun wrap(base: Context): Context {
        val language = current(base)
        val locale = localeOf(language) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
