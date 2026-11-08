package com.zerolinux.app.container

import org.json.JSONObject

/**
 * 四种发行版：枚举本身定义在 DistroCatalog.kt，这里只放容器侧的配置模型。
 */
enum class MirrorMode {
    DOMESTIC, OVERSEAS, CUSTOM;

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(value: String?): MirrorMode =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: DOMESTIC
    }
}

data class MirrorConfig(
    val mode: MirrorMode = MirrorMode.DOMESTIC,
    val customUrl: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.key)
        .put("customUrl", customUrl)

    companion object {
        fun fromJson(json: JSONObject?): MirrorConfig {
            if (json == null) return MirrorConfig()
            return MirrorConfig(
                mode = MirrorMode.fromKey(json.optString("mode")),
                customUrl = json.optString("customUrl")
            )
        }
    }
}

/**
 * 单个容器的完整配置。uuid 是目录名，永不变；id 是用户可见、可改的标识。
 */
data class ContainerConfig(
    val uuid: String,
    val id: String,
    val distro: DistroId,
    val abi: String,
    val mirror: MirrorConfig = MirrorConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    val initialized: Boolean = false,
    val rootfsReady: Boolean = false,
    /** 断点续跑标记，形如 "3/5"。 */
    val initStep: String = "",
    val lastError: String = "",
    val displayName: String = "",
    /** 容器内 OpenCode server 的登录密码（HTTP Basic，用户可改）。 */
    val opencodePassword: String = "admin",
    /** 容器内 OpenCode server 端口；0 表示尚未分配。 */
    val opencodePort: Int = 0
) {
    val label: String get() = displayName.ifBlank { id }

    fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid)
        .put("id", id)
        .put("distro", distro.key)
        .put("abi", abi)
        .put("mirror", mirror.toJson())
        .put("createdAt", createdAt)
        .put("initialized", initialized)
        .put("rootfsReady", rootfsReady)
        .put("initStep", initStep)
        .put("lastError", lastError)
        .put("displayName", displayName)
        .put("opencodePassword", opencodePassword)
        .put("opencodePort", opencodePort)

    companion object {
        fun fromJson(json: JSONObject): ContainerConfig = ContainerConfig(
            uuid = json.optString("uuid"),
            id = json.optString("id"),
            distro = DistroId.fromKey(json.optString("distro")),
            abi = json.optString("abi", "arm64-v8a"),
            mirror = MirrorConfig.fromJson(json.optJSONObject("mirror")),
            createdAt = json.optLong("createdAt", 0L),
            initialized = json.optBoolean("initialized", false),
            rootfsReady = json.optBoolean("rootfsReady", false),
            initStep = json.optString("initStep"),
            lastError = json.optString("lastError"),
            displayName = json.optString("displayName"),
            opencodePassword = json.optString("opencodePassword", "admin").ifBlank { "admin" },
            opencodePort = json.optInt("opencodePort", 0)
        )
    }
}
