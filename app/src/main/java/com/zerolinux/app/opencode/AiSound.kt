package com.zerolinux.app.opencode

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.opencode.mobile.OpenCodeMobile
import com.opencode.mobile.OpenCodeMobileSound

/**
 * AI 提示音的宿主播放器。
 *
 * 所有提示音都由宿主播放：AAR 自己的播放器只在宿主失败时兜底。不在按
 * 「AI 页面是否可见」切换播放方，是因为前后台切换的瞬间两边的可见性状态
 * 会短暂错位（WebView 已暂停但 Activity 还没 pause、Activity 已 resume 但
 * WebView 还没恢复），两边都可能以为对方会响，结果整声丢失。
 *
 * 播放统一走通知流（`USAGE_NOTIFICATION`）：与 AAR、系统通知同一条通道，
 * 后台、前台音量和行为一致，不会被媒体流的静音或 OEM 后台媒体限制影响。
 */
object AiSound {

    private const val TAG = "AiSound"
    private const val BOOST_MILLIBELS = 800

    /** 同一次完成可能被 WebView 与后台轮询各触发一次，窗口内不重启播放。 */
    private const val DEDUPE_MS = 700L

    private val lock = Any()

    private var player: MediaPlayer? = null
    private var boost: LoudnessEnhancer? = null
    private var startedAt = 0L

    /**
     * 安装到 AAR：每条提示音都先给宿主播放，只有确实没播起来才返回 false，
     * 让 AAR 用自己的播放器兜底。
     *
     * `Application.onCreate` 与每次挂 AI 顶栏时各调一次，防止 AAR 内部
     * 重建静态状态后丢掉注册。
     */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            OpenCodeMobile.setSoundHandler(object : OpenCodeMobileSound.Handler {
                override fun onSound(event: OpenCodeMobileSound.Event): Boolean {
                    return play(app)
                }
            })
        }.onFailure { Log.w(TAG, "install sound handler failed: ${it.message}") }
    }

    /**
     * 同步播放提示音。
     *
     * @return true 表示这条提示音已经在响（AAR 应抑制自己的播放器）；
     *         false 表示宿主没播成功，交给 AAR 用它的播放器兜底。
     */
    fun play(ctx: Context): Boolean {
        val app = ctx.applicationContext
        synchronized(lock) {
            // 切换前后台时，WebView 与后台轮询常常会对同一次完成各报一次。
            // 正在响的提示音不打断，重复事件直接当作已处理。
            val current = player
            if (current != null && SystemClock.elapsedRealtime() - startedAt < DEDUPE_MS) {
                val playing = runCatching { current.isPlaying }.getOrDefault(false)
                if (playing) return true
            }
            releaseLocked()
            if (startBundled(app)) return true
            releaseLocked()
            return startSystemRingtone(app)
        }
    }

    /** 播 AAR 合并进来的 `oc_notification`；用通知流，前后台都能正常出声。 */
    private fun startBundled(ctx: Context): Boolean {
        val id = ctx.resources.getIdentifier("oc_notification", "raw", ctx.packageName)
        if (id == 0) return false
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(ctx, Uri.parse("android.resource://${ctx.packageName}/$id"))
            mp.setOnCompletionListener {
                synchronized(lock) { if (player === it) releaseLocked() }
            }
            mp.setOnErrorListener { p, _, _ ->
                synchronized(lock) { if (player === p) releaseLocked() }
                true
            }
            mp.prepare()
            mp.setVolume(1f, 1f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                runCatching {
                    LoudnessEnhancer(mp.audioSessionId).apply {
                        setTargetGain(BOOST_MILLIBELS)
                        enabled = true
                    }
                }.getOrNull()?.let { boost = it }
            }
            player = mp
            startedAt = SystemClock.elapsedRealtime()
            mp.start()
            Log.i(TAG, "chime started (notification stream)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "bundled chime failed: ${t.message}")
            runCatching { mp.release() }
            boost?.let { runCatching { it.release() } }
            boost = null
            player = null
            startedAt = 0L
            false
        }
    }

    /** 资源缺失或解码失败时退回系统默认通知音，同样走通知流。 */
    private fun startSystemRingtone(ctx: Context): Boolean {
        return runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(ctx, uri) ?: return false
            ringtone.play()
            startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "chime started (system ringtone)")
            true
        }.getOrElse {
            Log.w(TAG, "chime failed: ${it.message}")
            false
        }
    }

    private fun releaseLocked() {
        boost?.let { runCatching { it.release() } }
        boost = null
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        player = null
        startedAt = 0L
    }
}
