package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

// 语音时长格式化：随 VoiceMessageDuration 由 app 侧 VoiceCapturePolicyTest 迁入。
class VoiceMessageDurationTest {

    @Test
    fun formatDuration_minutesSeconds() {
        assertEquals("0:00", VoiceMessageDuration.formatDuration(0))
        assertEquals("0:05", VoiceMessageDuration.formatDuration(5_000))
        assertEquals("1:01", VoiceMessageDuration.formatDuration(61_000))
    }

    @Test
    fun formatDuration_hourBoundary() {
        // 无小时位：59 分与 60 分边界逐字钉住
        assertEquals("59:59", VoiceMessageDuration.formatDuration(3_599_999))
        assertEquals("60:00", VoiceMessageDuration.formatDuration(3_600_000))
    }

    @Test
    fun formatDuration_truncatesSubSecond() {
        assertEquals("0:00", VoiceMessageDuration.formatDuration(999))
        assertEquals("0:01", VoiceMessageDuration.formatDuration(1_500))
    }

    @Test
    fun formatDuration_minuteBoundaries() {
        assertEquals("0:59", VoiceMessageDuration.formatDuration(59_000))
        assertEquals("1:00", VoiceMessageDuration.formatDuration(60_000))
        assertEquals("1:01", VoiceMessageDuration.formatDuration(61_500))
        assertEquals("10:00", VoiceMessageDuration.formatDuration(600_000))
    }
}
