package com.maodouchat.server.model

import kotlinx.serialization.Serializable


// Signal 密钥交换
@Serializable
data class UploadKeysRequest(
    val registrationId: Int,
    val deviceId: Int = 1,
    val deviceName: String? = null,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeys: List<PreKeyData>
)

@Serializable
data class PreKeyData(val keyId: Int, val publicKey: String)

@Serializable
data class PreKeyBundleResponse(
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

@Serializable
data class DeviceInfoResponse(
    val userId: String,
    val deviceId: Int,
    val deviceName: String = "我的设备",
    val identityKey: String,
    val lastSeenAt: Long? = null,
    val isCurrent: Boolean = false,
    val status: String = "CONFIRMED",
    val confirmedAt: Long? = null,
    val confirmedByDeviceId: Int? = null
)

@Serializable
data class UpdateDeviceNameRequest(val deviceName: String)

@Serializable
data class ConfirmDeviceRequest(
    val approverDeviceId: Int,
    val signature: String = ""
)

@Serializable
data class DevicePreKeyBundleResponse(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

// WebRTC 信令
@Serializable
data class SendSignalRequest(
    val toUserId: String,
    val type: String,
    val payload: String,
    val callId: String,
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
data class SignalMessageResponse(
    val id: String,
    val fromUserId: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
data class IceServerResponse(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
)

@Serializable
data class IceConfigResponse(
    val iceServers: List<IceServerResponse>,
    val expiresAt: Long,
    val turnEnabled: Boolean
)
