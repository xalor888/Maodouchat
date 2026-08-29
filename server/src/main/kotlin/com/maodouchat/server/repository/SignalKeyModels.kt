package com.maodouchat.server.repository

// 自 SignalKeyRepository 抽出的领域模型（B03：拆 DeviceRegistry/Store 前的模型分离）。

/** 设备状态常量（自 SignalKeyRepository companion 抽出）。 */
internal const val DEVICE_STATUS_CONFIRMED = "CONFIRMED"
internal const val DEVICE_STATUS_PENDING = "PENDING"

enum class DeleteDeviceResult { DELETED, NOT_FOUND, LAST_CONFIRMED }

data class DeleteDeviceOutcome(
    val result: DeleteDeviceResult,
    val revokedSessionIds: Set<String> = emptySet()
)

data class PreKeyUpload(val keyId: Int, val publicKeyBase64: String)
data class KeyData(val keyId: Int, val publicKeyBase64: String)

data class DeviceInfo(
    val userId: String,
    val deviceId: Int,
    val deviceName: String,
    val identityKey: String,
    val lastSeenAt: Long? = null,
    val isCurrent: Boolean = false,
    val status: String = DEVICE_STATUS_CONFIRMED,
    val confirmedAt: Long? = null,
    val confirmedByDeviceId: Int? = null
)

data class DeviceBundle(
    val userId: String,
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int?,
    val preKey: String?
)

enum class ConfirmDeviceResult {
    CONFIRMED,
    ALREADY_CONFIRMED,
    NOT_FOUND,
    APPROVER_NOT_TRUSTED,
    INVALID_PROOF,
    INVALID
}
