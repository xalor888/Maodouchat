package com.maodouchat.crypto

import java.util.concurrent.ConcurrentHashMap

/**
 * 解密失败如何停止：哪些结果应 ACK / 停止重试，哪些可重试以及上限。
 *
 * 纯决策 + 可选内存计数器。避免同一信封 Failed/Unsupported 被同步队列无限重拉，
 * 也避免 libsignal 对已失败密文反复尝试（可能推进 ratchet）。
 */
object DecryptFailurePolicy {
    const val MAX_RETRY_ATTEMPTS = 5
    const val MAX_TRACKED_ENVELOPES = 256

    enum class Disposition {
        /** 调用方应视为终态：ACK、写入占位、不再对同一密文调 SessionCipher。 */
        STOP,
        /** 会话/身份/epoch 仍可能恢复，允许再试。 */
        RETRY,
    }

    fun disposition(result: SignalProtocol.DecryptResult): Disposition = when (result) {
        is SignalProtocol.DecryptResult.Success,
        SignalProtocol.DecryptResult.Duplicate,
        SignalProtocol.DecryptResult.UnsupportedEnvelope,
        SignalProtocol.DecryptResult.NotForThisDevice -> Disposition.STOP
        SignalProtocol.DecryptResult.NoSession,
        SignalProtocol.DecryptResult.UntrustedIdentity,
        SignalProtocol.DecryptResult.FutureEpoch,
        SignalProtocol.DecryptResult.Failed -> Disposition.RETRY
    }

    /** 达到上限后，即使 [Disposition.RETRY] 也必须停，防止死循环重试同一条。 */
    fun shouldStop(result: SignalProtocol.DecryptResult, previousAttempts: Int): Boolean {
        if (disposition(result) == Disposition.STOP) return true
        return previousAttempts >= MAX_RETRY_ATTEMPTS
    }

    fun shouldSkipCryptoAttempt(previousFailures: Int): Boolean =
        previousFailures >= MAX_RETRY_ATTEMPTS

    fun envelopeFingerprint(senderId: String, content: String): String {
        var hash = 1125899906842597L
        val seed = senderId.length * 31L + content.length
        hash = hash * 31 + seed
        hash = hash * 31 + senderId.hashCode()
        hash = hash * 31 + content.hashCode()
        return "${senderId.length}:${content.length}:$hash"
    }
}

/**
 * 进程内信封重试计数。进程重启后归零，足以打断热循环；上限后返回 true（应 ACK）。
 */
class DecryptRetryTracker(
    private val maxAttempts: Int = DecryptFailurePolicy.MAX_RETRY_ATTEMPTS,
    private val maxTracked: Int = DecryptFailurePolicy.MAX_TRACKED_ENVELOPES,
) {
    private val attempts = ConcurrentHashMap<String, Int>()

    /**
     * @return true 时调用方应 ACK / 停止再拉这一条。
     */
    fun shouldAcknowledge(envelopeId: String, result: SignalProtocol.DecryptResult): Boolean {
        if (envelopeId.isBlank()) {
            return DecryptFailurePolicy.disposition(result) == DecryptFailurePolicy.Disposition.STOP
        }
        if (DecryptFailurePolicy.disposition(result) == DecryptFailurePolicy.Disposition.STOP) {
            attempts.remove(envelopeId)
            return true
        }
        val next = (attempts[envelopeId] ?: 0) + 1
        if (next >= maxAttempts) {
            attempts.remove(envelopeId)
            evictIfNeeded()
            return true
        }
        attempts[envelopeId] = next
        evictIfNeeded()
        return false
    }

    fun failureCount(fingerprint: String): Int = attempts[fingerprint] ?: 0

    fun recordCryptoFailure(fingerprint: String) {
        if (fingerprint.isBlank()) return
        attempts[fingerprint] = (attempts[fingerprint] ?: 0) + 1
        evictIfNeeded()
    }

    fun clear(fingerprint: String) {
        attempts.remove(fingerprint)
    }

    fun clearAll() {
        attempts.clear()
    }

    private fun evictIfNeeded() {
        if (attempts.size <= maxTracked) return
        val extra = attempts.size - maxTracked
        attempts.keys.take(extra).forEach { attempts.remove(it) }
    }
}
