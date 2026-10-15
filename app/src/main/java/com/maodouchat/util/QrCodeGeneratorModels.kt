package com.maodouchat.util

sealed class QrTarget {
    data class User(val userId: String) : QrTarget()
    data class Chat(val chatId: String) : QrTarget()
    data class ChatInvite(val token: String) : QrTarget()
    data class Safety(
        val ownerUserId: String,
        val ownerDeviceId: Int,
        val peerUserId: String,
        val peerDeviceId: Int,
        val safetyCode: String? = null,
        val ownerIdentityFingerprint: String? = null,
        val peerIdentityFingerprint: String? = null
    ) : QrTarget()
}
