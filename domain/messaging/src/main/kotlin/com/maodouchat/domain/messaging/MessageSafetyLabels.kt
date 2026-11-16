package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageSafetyScanner

// 安全告警码→详情种类；R.string 映射留在 app 侧，host 只有可疑链接那一支携带。
enum class MessageSafetyDetailKind {
    SuspiciousLink,
    SuspiciousLinkHost,
    PaymentInducement,
    Impersonation,
    CredentialRequest,
    SensitiveData,
    Generic,
}

fun messageSafetyDetailKind(code: String, matched: String?): Pair<MessageSafetyDetailKind, String?> =
    when (code) {
        MessageSafetyScanner.CODE_SUSPICIOUS_LINK ->
            if (matched.isNullOrBlank()) {
                MessageSafetyDetailKind.SuspiciousLink to null
            } else {
                MessageSafetyDetailKind.SuspiciousLinkHost to matched
            }
        MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT -> MessageSafetyDetailKind.PaymentInducement to null
        MessageSafetyScanner.CODE_IMPERSONATION -> MessageSafetyDetailKind.Impersonation to null
        MessageSafetyScanner.CODE_CREDENTIAL_REQUEST -> MessageSafetyDetailKind.CredentialRequest to null
        MessageSafetyScanner.CODE_SENSITIVE_DATA -> MessageSafetyDetailKind.SensitiveData to null
        else -> MessageSafetyDetailKind.Generic to null
    }
