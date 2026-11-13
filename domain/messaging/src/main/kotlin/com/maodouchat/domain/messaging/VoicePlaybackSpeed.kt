package com.maodouchat.domain.messaging

// 语音播放倍速档位环：由 app 侧 VoicePlayer 逐字迁入。
object VoicePlaybackSpeed {
    // 允许的播放倍速（循环切换）。
    val SPEED_STEPS: FloatArray = floatArrayOf(1f, 1.5f, 2f, 0.5f)

    // 下一档倍速（纯函数，供 UI/单测）。
    fun nextSpeed(current: Float): Float {
        val steps = SPEED_STEPS
        val idx = steps.indexOfFirst { it == current }.let { if (it < 0) 0 else it }
        return steps[(idx + 1) % steps.size]
    }

    fun formatSpeedLabel(speed: Float): String {
        return when (speed) {
            0.5f -> "0.5x"
            1.5f -> "1.5x"
            2f -> "2x"
            else -> "1x"
        }
    }
}
