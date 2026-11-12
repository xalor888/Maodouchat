package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.domain.calls.CallIncomingCallMessage
import com.maodouchat.domain.calls.CallOutgoingCallMessage

/** 呼入控制器文案种类到 R.string 的映射。 */
internal fun callIncomingCallMessageResId(message: CallIncomingCallMessage): Int = when (message) {
    CallIncomingCallMessage.NOTIFY_BUSY_FAILED -> R.string.call_notify_busy_failed
    CallIncomingCallMessage.GROUP_NAME -> R.string.call_group_name
    CallIncomingCallMessage.INCOMING_INCOMPLETE -> R.string.call_incoming_incomplete
    CallIncomingCallMessage.SESSION_EXPIRED -> R.string.call_session_expired
    CallIncomingCallMessage.WEBRTC_DOWNLOAD_FAILED -> R.string.call_webrtc_download_failed
    CallIncomingCallMessage.ANSWER_FAILED -> R.string.call_answer_failed
    CallIncomingCallMessage.UNKNOWN_ERROR -> R.string.call_unknown_error
    CallIncomingCallMessage.NOTIFY_REJECT_FAILED -> R.string.call_notify_reject_failed
}

/** 呼出控制器文案种类到 R.string 的映射。 */
internal fun callOutgoingCallMessageResId(message: CallOutgoingCallMessage): Int = when (message) {
    CallOutgoingCallMessage.CALLS_DISABLED -> R.string.calls_disabled
    CallOutgoingCallMessage.VIDEO_CALL_DISABLED -> R.string.video_call_disabled
    CallOutgoingCallMessage.VOICE_CALL_DISABLED -> R.string.voice_call_disabled
    CallOutgoingCallMessage.SESSION_EXPIRED -> R.string.call_session_expired
    CallOutgoingCallMessage.GROUP_CALL -> R.string.chat_group_call
    CallOutgoingCallMessage.GROUP_NAME -> R.string.call_group_name
    CallOutgoingCallMessage.GROUP_MESH_LIMIT -> R.string.call_group_mesh_limit
    CallOutgoingCallMessage.WEBRTC_DOWNLOAD_FAILED -> R.string.call_webrtc_download_failed
    CallOutgoingCallMessage.INITIALIZATION_FAILED -> R.string.call_initialization_failed
    CallOutgoingCallMessage.UNKNOWN_ERROR -> R.string.call_unknown_error
    CallOutgoingCallMessage.GROUP_START_FAILED -> R.string.call_group_start_failed
}
