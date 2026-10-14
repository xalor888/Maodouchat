package com.maodouchat.security

enum class SafetyScanStatus {
    SESSION_EXPIRED,
    WRONG_ACCOUNT,
    WRONG_DEVICE,
    NO_SESSION,
    WRONG_DEVICE_QR,
    FINGERPRINT_MATCH,
    FINGERPRINT_MISMATCH,
    INVALID_CODE,
    CODE_MATCH,
    CODE_MISMATCH,
}

/** QR 载荷里参与核验的字段（由 ui 侧从 `QrCodeGenerator.QrTarget.Safety` 逐字映射）。 */
data class SafetyScanRequest(
    val ownerUserId: String,
    val ownerDeviceId: Int,
    val peerUserId: String,
    val peerDeviceId: Int,
    val ownerIdentityFingerprint: String?,
    val peerIdentityFingerprint: String?,
    val safetyCode: String?,
)
