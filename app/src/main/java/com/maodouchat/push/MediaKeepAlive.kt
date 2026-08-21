package com.maodouchat.push

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.maodouchat.R

/**
 * 9.3xx：「音乐播放器」保活（用户指定的伪装形态）。
 *
 * 挂一个 PLAYING 状态的 MediaSession + 循环播放无声音频 + 媒体样式常驻通知：
 * 系统把本进程视为活跃媒体应用（媒体豁免 + 更高的后台存活权重），
 * 同时不产生任何可听声音（音频为纯静音 WAV）。
 *
 * 仅由 [PushKeepAliveService] 在模式为 media 时启用；真实通话期间
 * [suspend]/[resume] 由服务随来电模式一起调用（避免与通话音频焦点冲突）。
 */
class MediaKeepAlive(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var session: MediaSessionCompat? = null

    fun start() {
        if (session != null) return
        try {
            // 9.4xx：prepareAsync——同步 prepare() 在服务主线程会 ANR
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(
                    context,
                    android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.keepalive_silence}")
                )
                isLooping = true
                setOnPreparedListener { mp ->
                    runCatching { mp.start() }
                }
                prepareAsync()
            }
            mediaPlayer = player

            val s = MediaSessionCompat(context, "maodouchat_push_keepalive")
            s.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Background service")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Maodouchat")
                    .build()
            )
            s.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
            s.isActive = true
            session = s
            active = this
            Log.i("MediaKeepAlive", "media session active (silent loop)")
        } catch (error: Exception) {
            Log.w("MediaKeepAlive", "start failed", error)
            stop()
        }
    }

    fun stop() {
        runCatching { session?.isActive = false }
        runCatching { session?.release() }
        session = null
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        if (active === this) active = null
    }

    companion object {
        /** 当前活跃的媒体保活实例（真实通话开始时统一挂起）。 */
        @Volatile
        var active: MediaKeepAlive? = null
            private set

        fun stopActive() {
            active?.stop()
        }
    }
}
