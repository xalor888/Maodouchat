package com.maodouchat.util

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.maodouchat.MaodouchatApp

/**
 * 0.81：应用内提示音（ToneGenerator 免音频资源）。
 * 受 RuntimeFlags.IN_APP_SOUNDS 门控（服务端可整体开关）；此前该 flag 只写入从未生效。
 */
object InAppSoundPlayer {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 消息发送成功提示音。 */
    fun playSendTone() {
        if (!RuntimeFlags.isEnabled(MaodouchatApp.instance, RuntimeFlags.IN_APP_SOUNDS)) return
        play(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    /** 收到新消息提示音（前台会话场景）。 */
    fun playReceiveTone() {
        if (!RuntimeFlags.isEnabled(MaodouchatApp.instance, RuntimeFlags.IN_APP_SOUNDS)) return
        play(ToneGenerator.TONE_PROP_BEEP2, 80)
    }

    private fun play(tone: Int, volume: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
            generator.startTone(tone, 120)
            mainHandler.postDelayed({ runCatching { generator.release() } }, 400)
        }
    }
}
