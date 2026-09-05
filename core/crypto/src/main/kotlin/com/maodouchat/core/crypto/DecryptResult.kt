package com.maodouchat.core.crypto

/** 解密结果（M03）：1:1 会话密文解密后的域结果，纯类型无 libsignal 依赖。 */
sealed class DecryptResult {
    data class Success(val plaintext: String) : DecryptResult()
    data object UnsupportedEnvelope : DecryptResult()
    data object NotForThisDevice : DecryptResult()
    data object NoSession : DecryptResult()
    data object UntrustedIdentity : DecryptResult()
    data object FutureEpoch : DecryptResult()
    data object Failed : DecryptResult()
    data object Duplicate : DecryptResult()
}
