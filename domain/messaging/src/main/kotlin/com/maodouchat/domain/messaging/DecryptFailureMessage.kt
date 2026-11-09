package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.DecryptResult

/**
 * 解密失败的诊断码：收件箱同步器据此区分重试与死信（见 MessagingV2InboxFailurePolicy），
 * 所以各失败码必须互不相同。仅用于失败变体；成功与重复没有失败码。
 */
fun decryptFailureMessage(result: DecryptResult): String = when (result) {
    DecryptResult.NoSession -> "messaging_v2_no_session"
    DecryptResult.UntrustedIdentity -> "messaging_v2_untrusted_identity"
    DecryptResult.FutureEpoch -> "messaging_v2_future_group_revision"
    DecryptResult.NotForThisDevice -> "messaging_v2_wrong_device"
    DecryptResult.UnsupportedEnvelope -> "messaging_v2_unsupported_ciphertext"
    DecryptResult.Failed -> "messaging_v2_decrypt_failed"
    is DecryptResult.Success, DecryptResult.Duplicate ->
        error("decryptFailureMessage: $result is not a failure")
}
