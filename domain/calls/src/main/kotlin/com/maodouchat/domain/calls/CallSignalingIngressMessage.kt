package com.maodouchat.domain.calls

// 入站信令向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧映射提供。
enum class CallSignalingIngressMessage {
    RATE_LIMITED,
    FETCH_SIGNALING_FAILED,
    NOTIFY_BUSY_FAILED,
    PEER_REJECTED,
    PEER_BUSY,
    HANDLE_SIGNALING_FAILED,
    UNKNOWN_ERROR,
}
