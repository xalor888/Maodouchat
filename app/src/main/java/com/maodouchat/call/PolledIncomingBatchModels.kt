package com.maodouchat.call

import com.maodouchat.webrtc.SignalMessage

/** 未接来电墓碑的参数：调用方用它调 MissedCallRecorder（带会话门禁的写路径）。 */
data class MissedRecordParams(
    val signalingCallId: String,
    val fromUserId: String,
    val callerName: String,
    val isVideo: Boolean,
    val isGroup: Boolean,
)

data class TerminalDecision(
    /** 必须取消该 callId 的来电通知，并向 CallActionBus 转发 hang-up。 */
    val callId: String,
    /** 终端命中了本地 pending 的响铃：清 pending 并记未接来电墓碑；null = 只取消+转发。 */
    val missedRecord: MissedRecordParams?,
)

data class PolledIncomingBatchDecision(
    val terminals: List<TerminalDecision>,
    /** preferCallId 已被终端覆盖或库中已无其 offer（幽灵响铃）：取消该通知。 */
    val cancelPreferCallId: Boolean,
    /** 首选 offer；null = 无可响铃 offer。 */
    val primary: SignalMessage?,
    /** 是否走导航接线；false = WS 路径已送达同 callId，轮询不再重复响铃。 */
    val shouldNavigate: Boolean,
    /** 其余 offer：逐条回 busy。 */
    val busyReplies: List<SignalMessage>,
)
