package com.maodouchat.domain.calls

// 出站信令发送与 ICE 恢复向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧映射提供。
enum class CallSignalingMessage {
    SEND_SDP_FAILED,
    SEND_CANDIDATE_FAILED,
    SESSION_EXPIRED,
    ERROR_WITH_REASON,
    NETWORK_DISCONNECTED,
}
