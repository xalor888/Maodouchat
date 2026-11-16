package com.maodouchat.domain.contacts

import java.util.Locale

// 账号设备身份密钥指纹缩写（逐字平移自 AccountSecurityDisplayRows）。
object AccountDeviceFingerprintPolicy {
    const val IDENTITY_KEY_HEAD_LENGTH = 8
    const val IDENTITY_KEY_TAIL_LENGTH = 6
    const val FINGERPRINT_LENGTH = 8

    fun identityKeyHead(identityKey: String): String = identityKey.take(IDENTITY_KEY_HEAD_LENGTH)
    fun identityKeyTail(identityKey: String): String = identityKey.takeLast(IDENTITY_KEY_TAIL_LENGTH)

    fun shortFingerprint(fingerprint: String?): String? =
        fingerprint?.replace(" ", "")?.take(FINGERPRINT_LENGTH)?.uppercase(Locale.US)
}
