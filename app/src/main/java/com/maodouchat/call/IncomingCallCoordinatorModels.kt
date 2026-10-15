package com.maodouchat.call

import com.maodouchat.webrtc.CallType

data class PendingIncomingCall(
    val contactId: String,
    val contactName: String,
    val callType: CallType,
    val offerSdp: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val receivedAtMillis: Long = System.currentTimeMillis(),
    /** 8.56：系统 Telecom 来电 UI 已点「接听」——进入来电路由后直接自动接听。 */
    val autoAnswer: Boolean = false,
)
