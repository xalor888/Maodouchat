package com.maodouchat.util

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内提示音：与托盘渠道共用 [R.raw.notify_message]（短衰减铃，不是 ToneGenerator 滴滴）。
 * 受 RuntimeFlags.IN_APP_SOUNDS 门控。
 */
object InAppSoundPlayer {

    private val lastReceiveAt = AtomicLong(0L)
    private const val RECEIVE_DEBOUNCE_MS = 900L

    @Volatile
    private var pool: SoundPool? = null
    @Volatile
    private var sampleId: Int = 0
    @Volatile
    private var sampleReady: Boolean = false

    /** 消息发送成功提示音。 */
    fun playSendTone() {
        if (!RuntimeFlags.isEnabled(MaodouchatApp.instance, RuntimeFlags.IN_APP_SOUNDS)) return
        playSample(volume = 0.55f)
    }

    /** 收到新消息提示音（前台会话场景）。进出会话不得重放。 */
    fun playReceiveTone() {
        if (!RuntimeFlags.isEnabled(MaodouchatApp.instance, RuntimeFlags.IN_APP_SOUNDS)) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastReceiveAt.get()
        if (now - previous < RECEIVE_DEBOUNCE_MS) return
        if (!lastReceiveAt.compareAndSet(previous, now)) return
        playSample(volume = 0.72f)
    }

    private fun playSample(volume: Float) {
        runCatching {
            val context = MaodouchatApp.instance
            val soundPool = pool ?: synchronized(this) {
                pool ?: SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                    .also { created ->
                        created.setOnLoadCompleteListener { _, sample, status ->
                            if (status == 0 && sample == sampleId) sampleReady = true
                        }
                        sampleId = created.load(context, R.raw.notify_message, 1)
                        pool = created
                    }
            }
            if (sampleReady && sampleId != 0) {
                soundPool.play(sampleId, volume, volume, 1, 0, 1f)
            } else {
                soundPool.setOnLoadCompleteListener { _, sample, status ->
                    if (status == 0 && sample == sampleId) {
                        sampleReady = true
                        soundPool.play(sampleId, volume, volume, 1, 0, 1f)
                    }
                }
            }
        }
    }
}
