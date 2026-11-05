package com.maodouchat.core.model

// 原 app 侧 Kind：:core:model 已有 QrScanFeedbackModels.Kind，改名避让。
enum class AttachmentErrorKind {
    TOO_LARGE,
    INVALID_REFERENCE,
    INTEGRITY_FAILED,
    CONTENT_MISMATCH,
    FALLBACK
}
