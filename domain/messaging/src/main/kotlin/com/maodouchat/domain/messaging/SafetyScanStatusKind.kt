package com.maodouchat.domain.messaging

import com.maodouchat.core.session.SafetyScanStatus

// 安全码核验状态种类；三支文案带设备号实参（WRONG_DEVICE 带 peerDeviceId，
// FINGERPRINT_MATCH/CODE_MATCH 带 ownerDeviceId），其余无参。
enum class SafetyScanStatusKind {
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

fun safetyScanStatusKind(
    status: SafetyScanStatus,
    peerDeviceId: Int,
    ownerDeviceId: Int,
): Pair<SafetyScanStatusKind, Int?> =
    when (status) {
        SafetyScanStatus.SESSION_EXPIRED -> SafetyScanStatusKind.SESSION_EXPIRED to null
        SafetyScanStatus.WRONG_ACCOUNT -> SafetyScanStatusKind.WRONG_ACCOUNT to null
        SafetyScanStatus.WRONG_DEVICE -> SafetyScanStatusKind.WRONG_DEVICE to peerDeviceId
        SafetyScanStatus.NO_SESSION -> SafetyScanStatusKind.NO_SESSION to null
        SafetyScanStatus.WRONG_DEVICE_QR -> SafetyScanStatusKind.WRONG_DEVICE_QR to null
        SafetyScanStatus.FINGERPRINT_MATCH -> SafetyScanStatusKind.FINGERPRINT_MATCH to ownerDeviceId
        SafetyScanStatus.FINGERPRINT_MISMATCH -> SafetyScanStatusKind.FINGERPRINT_MISMATCH to null
        SafetyScanStatus.INVALID_CODE -> SafetyScanStatusKind.INVALID_CODE to null
        SafetyScanStatus.CODE_MATCH -> SafetyScanStatusKind.CODE_MATCH to ownerDeviceId
        SafetyScanStatus.CODE_MISMATCH -> SafetyScanStatusKind.CODE_MISMATCH to null
    }
