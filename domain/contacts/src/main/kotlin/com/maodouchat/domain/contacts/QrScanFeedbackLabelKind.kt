package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Kind

// 扫码反馈 Kind→文案种类；R.string 映射留在 app 侧。
enum class QrScanFeedbackLabelKind {
    INVALID_PAYLOAD,
    SESSION_EXPIRED,
    USER_NOT_FOUND,
    INVITE_INVALID_OR_EXPIRED,
    INVITE_BLOCKED,
    GROUP_FULL,
    NETWORK,
    UNKNOWN,
}

fun qrScanFeedbackLabelKind(kind: Kind): QrScanFeedbackLabelKind =
    when (kind) {
        Kind.INVALID_PAYLOAD -> QrScanFeedbackLabelKind.INVALID_PAYLOAD
        Kind.SESSION_EXPIRED -> QrScanFeedbackLabelKind.SESSION_EXPIRED
        Kind.USER_NOT_FOUND -> QrScanFeedbackLabelKind.USER_NOT_FOUND
        Kind.INVITE_INVALID_OR_EXPIRED -> QrScanFeedbackLabelKind.INVITE_INVALID_OR_EXPIRED
        Kind.INVITE_BLOCKED -> QrScanFeedbackLabelKind.INVITE_BLOCKED
        Kind.GROUP_FULL -> QrScanFeedbackLabelKind.GROUP_FULL
        Kind.NETWORK -> QrScanFeedbackLabelKind.NETWORK
        Kind.UNKNOWN -> QrScanFeedbackLabelKind.UNKNOWN
    }
