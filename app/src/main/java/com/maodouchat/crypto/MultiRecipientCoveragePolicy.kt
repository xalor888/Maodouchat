package com.maodouchat.crypto

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind

/** Pure coverage checks shared by direct multi-device envelopes and their tests. */
internal object MultiRecipientCoveragePolicy {
    data class Target(val userId: String, val deviceId: Int)

    fun normalizeRequiredRecipientId(recipientId: String): String? =
        recipientId.trim().takeIf(String::isNotBlank)

    /** Every required user must have at least one concrete encrypted device target. */
    fun requiredRecipientsCovered(
        requiredRecipientIds: Collection<String>,
        targets: Collection<Target>,
    ): Boolean {
        val required = requiredRecipientIds.map(String::trim).filter(String::isNotBlank).toSet()
        if (required.isEmpty()) return true
        val covered = targets.map { it.userId.trim() }.filter(String::isNotBlank).toSet()
        return required.all { it in covered }
    }

    /**
     * Every required concrete device must have an envelope entry.
     *
     * A user-level check is insufficient for multi-device direct messages: one
     * successfully encrypted device must not hide a second device that failed.
     */
    fun requiredTargetsCovered(
        requiredTargets: Collection<Target>,
        targets: Collection<Target>,
    ): Boolean {
        val required = requiredTargets.mapNotNull(::normalizeTarget).toSet()
        if (required.isEmpty()) return true
        val covered = targets.mapNotNull(::normalizeTarget).toSet()
        return required.all { it in covered }
    }

    /** Required users that have no successfully encrypted device target. */
    fun missingRequiredRecipients(
        requiredRecipientIds: Collection<String>,
        targets: Collection<Target>,
    ): Set<String> {
        val required = requiredRecipientIds.map(String::trim).filter(String::isNotBlank).toSet()
        val covered = targets.map { it.userId.trim() }.filter(String::isNotBlank).toSet()
        return required - covered
    }

    /** Required concrete devices for which no ciphertext entry was produced. */
    fun missingRequiredTargets(
        requiredTargets: Collection<Target>,
        targets: Collection<Target>,
    ): Set<Target> {
        val required = requiredTargets.mapNotNull(::normalizeTarget).toSet()
        val covered = targets.mapNotNull(::normalizeTarget).toSet()
        return required - covered
    }

    /**
     * Preserve a retryable discovery/session failure for an uncovered required user.
     * Best-effort fan-out callers pass no required users and therefore never surface it.
     */
    fun transientFailureForMissingRecipients(
        requiredRecipientIds: Collection<String>,
        targets: Collection<Target>,
        failuresByRecipient: Map<String, Throwable>,
    ): TransientCoverageException? {
        val normalizedFailures = failuresByRecipient.entries.associate { (recipientId, error) ->
            recipientId.trim() to error
        }
        return missingRequiredRecipients(requiredRecipientIds, targets)
            .asSequence()
            .mapNotNull(normalizedFailures::get)
            .firstOrNull(::isTransient)
            ?.let { error ->
                TransientCoverageException(
                    message = "required recipient coverage transient failure",
                    cause = error,
                )
            }
    }

    /** Preserve a retryable failure for an uncovered concrete device target. */
    fun transientFailureForMissingTargets(
        requiredTargets: Collection<Target>,
        targets: Collection<Target>,
        failuresByTarget: Map<Target, Throwable>,
    ): TransientCoverageException? {
        val normalizedFailures = failuresByTarget.entries.mapNotNull { (target, error) ->
            normalizeTarget(target)?.let { normalizedTarget -> normalizedTarget to error }
        }.toMap()
        return missingRequiredTargets(requiredTargets, targets)
            .asSequence()
            .mapNotNull(normalizedFailures::get)
            .firstOrNull(::isTransient)
            ?.let { error ->
                TransientCoverageException(
                    message = "required recipient device coverage transient failure",
                    cause = error,
                )
            }
    }

    fun isTransient(error: Throwable?): Boolean {
        var current = error
        repeat(6) {
            when (current) {
                is SignalExchangeException -> {
                    return when (current.failure) {
                        SignalExchangeFailure.NETWORK,
                        SignalExchangeFailure.TIMEOUT -> true
                        SignalExchangeFailure.HTTP -> current.statusCode.isTransientHttpStatus()
                        else -> false
                    }
                }
                is ApiException -> {
                    return when (current.kind) {
                        ApiFailureKind.NETWORK,
                        ApiFailureKind.TIMEOUT -> true
                        ApiFailureKind.HTTP -> current.statusCode.isTransientHttpStatus()
                        else -> false
                    }
                }
                is java.net.SocketTimeoutException,
                is java.io.IOException -> return true
            }
            current = current?.cause
        }
        return false
    }

    private fun Int?.isTransientHttpStatus(): Boolean =
        this == 408 || this == 429 || (this != null && this in 500..599)

    private fun normalizeTarget(target: Target): Target? =
        target.userId.trim().takeIf(String::isNotBlank)?.let { userId ->
            target.deviceId.takeIf { it > 0 }?.let { deviceId -> Target(userId, deviceId) }
        }
}
