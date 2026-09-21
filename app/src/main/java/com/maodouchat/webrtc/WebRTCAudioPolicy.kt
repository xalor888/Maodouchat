package com.maodouchat.webrtc

/**
 * 音频约束的纯策略（G178 从 WebRTCManager 抽出）。
 *
 * 三个键名用的是**标准名称**（`echoCancellation` 等），
 * 不是已废弃的 `goog*` 前缀——改动时勿回退。
 */

/** 标准音频约束：回声消除 + 自动增益 + 噪声抑制。 */
internal fun standardAudioConstraints(): List<Pair<String, String>> = listOf(
    "echoCancellation" to "true",
    "autoGainControl" to "true",
    "noiseSuppression" to "true",
)
