package com.zerolinux.app.opencode

import android.content.Context
import com.zerolinux.app.core.AppPaths
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 记录退出前所在页面，供 LaunchActivity 重启后恢复。
 *
 * 多进程下不能再用 SharedPreferences：每个进程都会缓存自己的实例，
 * `:terminal` 写入的 last_screen、`:ai` 写入的 last_ai_route 在主进程里
 * 可能一直是旧值。这里改成带文件锁的原子 JSON，读写都直接落盘，
 * 主进程 / :terminal / :ai 看到的是同一份状态。
 */
object AppStateStore {

    private const val FILE_NAME = "app_state.json"
    private const val LOCK_NAME = "app_state.lock"

    private const val KEY_LAST_SCREEN = "last_screen"
    private const val KEY_LAST_AI_UUID = "last_ai_uuid"
    private const val KEY_LAST_AI_ROUTE = "last_ai_route"
    private const val KEY_LAST_AI_SERVER = "last_ai_server"

    // 旧版本单进程时写在 SharedPreferences 里的值。首次读取 JSON 不存在时
    // 作为迁移来源；一旦 app_state.json 出现，就不再回读，避免旧缓存覆盖新值。
    private const val LEGACY_PREF = "zl_app_state"

    fun setLastScreen(ctx: Context, screen: String) {
        mutate(ctx) { it.put(KEY_LAST_SCREEN, screen) }
    }

    fun lastScreen(ctx: Context): String? = readString(ctx, KEY_LAST_SCREEN)

    /** 最后一次进入 AI 时使用的容器（服务器 id 由 uuid 派生）。 */
    fun setLastAiUuid(ctx: Context, uuid: String) {
        mutate(ctx) { it.put(KEY_LAST_AI_UUID, uuid) }
    }

    fun lastAiUuid(ctx: Context): String? = readString(ctx, KEY_LAST_AI_UUID)

    fun clearLastAiUuid(ctx: Context) {
        mutate(ctx) { it.remove(KEY_LAST_AI_UUID) }
    }

    /**
     * AI 页面状态：AAR 的 hash 路由 + 当前服务器 id。
     *
     * 用户从 AI 切回终端时 WebView 可能被销毁，重新进入必须直接打开
     * 原来的页面（例如某个会话），而不是回到首页。
     */
    fun setLastAiPage(ctx: Context, route: String, serverId: String) {
        mutate(ctx) {
            it.put(KEY_LAST_AI_ROUTE, route)
            it.put(KEY_LAST_AI_SERVER, serverId)
        }
    }

    fun lastAiRoute(ctx: Context): String? = readString(ctx, KEY_LAST_AI_ROUTE)

    fun lastAiServer(ctx: Context): String? = readString(ctx, KEY_LAST_AI_SERVER)

    fun clearLastAiPage(ctx: Context) {
        mutate(ctx) {
            it.remove(KEY_LAST_AI_ROUTE)
            it.remove(KEY_LAST_AI_SERVER)
        }
    }

    @Synchronized
    private fun readString(ctx: Context, key: String): String? {
        val file = stateFile(ctx)
        val json = if (file.isFile) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            legacyJson(ctx)
        }
        return json.optString(key, "").takeIf { it.isNotEmpty() }
    }

    @Synchronized
    private fun mutate(ctx: Context, block: (JSONObject) -> Unit) {
        val app = ctx.applicationContext
        val root = AppPaths.root(app)
        runCatching { root.mkdirs() }
        val lockFile = File(root, LOCK_NAME)
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use {
                val file = stateFile(app)
                val json = if (file.isFile) {
                    runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
                } else {
                    legacyJson(app)
                }
                block(json)
                writeAtomic(file, json.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun stateFile(ctx: Context): File = File(AppPaths.root(ctx), FILE_NAME)

    private fun legacyJson(ctx: Context): JSONObject {
        val prefs = ctx.applicationContext
            .getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE)
        val json = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (value is String) json.put(key, value)
        }
        return json
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: return
        val tmp = File(dir, "$FILE_NAME.${android.os.Process.myPid()}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 极少数文件系统不支持同目录 rename：退化为覆盖写，但仍持锁。
                FileOutputStream(target).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
