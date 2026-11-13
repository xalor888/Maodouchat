package com.maodouchat.domain.messaging

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// 录音振幅归一化：由 app 侧 VoiceRecorder.companion 逐字迁入。
object VoiceCaptureAmplitude {
    private const val AMPLITUDE_FLOOR = 1.0
    private const val AMPLITUDE_CEILING = 32767.0

    // 将 MediaRecorder 原始振幅映射到 0..1。
    fun normalizeAmplitude(raw: Int): Float {
        if (raw <= 0) return 0f
        val clamped = min(raw.toDouble(), AMPLITUDE_CEILING)
        val db = 20.0 * ln(max(clamped, AMPLITUDE_FLOOR) / AMPLITUDE_FLOOR)
        // 约 0..90 dB 压到 0..1，低音量仍可见一点起伏
        return (db / 90.0).toFloat().coerceIn(0f, 1f)
    }
}
