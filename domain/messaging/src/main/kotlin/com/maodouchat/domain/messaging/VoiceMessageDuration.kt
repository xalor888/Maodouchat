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
}
