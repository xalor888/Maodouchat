package com.maodouchat.domain.calls

// 呼出控制器文案种类；R.string 映射在 app 侧。
enum class CallOutgoingCallMessage {
    CALLS_DISABLED,
    VIDEO_CALL_DISABLED,
    VOICE_CALL_DISABLED,
    SESSION_EXPIRED,
    GROUP_CALL,
    GROUP_NAME,
    GROUP_MESH_LIMIT,
    WEBRTC_DOWNLOAD_FAILED,
    INITIALIZATION_FAILED,
    UNKNOWN_ERROR,
    GROUP_START_FAILED,
}
