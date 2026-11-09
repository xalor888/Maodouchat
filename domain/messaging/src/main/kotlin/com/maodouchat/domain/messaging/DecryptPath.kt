package com.maodouchat.domain.messaging

/** Sender Key 密文的类型值：群解密与缺会话补发都认这个值。 */
const val SENDER_KEY_CIPHERTEXT_TYPE = "SENDER_KEY"

/** 解密路径：Sender Key 密文走群解密，其余走设备密文解密。 */
enum class DecryptPath {
    GROUP,
    DEVICE,
}

fun decideDecryptPath(ciphertextType: String): DecryptPath =
    if (ciphertextType == SENDER_KEY_CIPHERTEXT_TYPE) DecryptPath.GROUP else DecryptPath.DEVICE
