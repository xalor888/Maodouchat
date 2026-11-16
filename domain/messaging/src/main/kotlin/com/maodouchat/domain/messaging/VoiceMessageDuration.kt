package com.maodouchat.domain.messaging

// 语音时长 "m:ss"：由 app 侧 VoiceRecorder.companion 逐字迁入。
// 刻意不收敛到 CallHistoryTime.formatDuration：后者对 <=0 返回空串，
// 录音指示器/语音预览在 0 毫秒时要显示 "0:00"。
object VoiceMessageDuration {
    fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val secs = seconds % 60
        return "%d:%02d".format(minutes, secs)
    }

    // 媒体中心语音行：毫秒向上取整为秒，<=0 毫秒无时长。
    fun ceilSeconds(millis: Long): Long? =
        if (millis <= 0L) null else (millis + 999L) / 1000L

    // 语音气泡：毫秒向下取整为秒，不足 1 秒按 1 秒计。
    fun floorSecondsAtLeastOne(millis: Long): Long = (millis / 1000L).coerceAtLeast(1L)
}
