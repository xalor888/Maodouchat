package com.maodouchat.domain.calls

// 呼入控制器文案种类；R.string 映射在 app 侧。
enum class CallIncomingCallMessage {
    NOTIFY_BUSY_FAILED,
    GROUP_NAME,
    INCOMING_INCOMPLETE,
    SESSION_EXPIRED,
    WEBRTC_DOWNLOAD_FAILED,
    ANSWER_FAILED,
    UNKNOWN_ERROR,
    NOTIFY_REJECT_FAILED,
}
