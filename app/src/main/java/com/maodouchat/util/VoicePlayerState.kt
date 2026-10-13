package com.maodouchat.util

/**
 * VoicePlayer 播放状态：messageId + 进度 + 倍速 + 听筒/扬声器，供 UI 订阅。
 */
data class VoicePlayerState(
    val messageId: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val durationMs: Long = 0L,
    /** 0.5 / 1.0 / 1.5 / 2.0 */
    val speed: Float = 1f,
    /** true = 听筒，false = 扬声器（媒体） */
    val earpiece: Boolean = false,
)
