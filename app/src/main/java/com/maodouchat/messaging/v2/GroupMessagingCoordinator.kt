package com.maodouchat.messaging.v2

import com.maodouchat.network.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CancellationException

data class GroupMessagingSession(
    val ownerUserId: String,
    val authToken: String,
    val deviceId: Int,
)

/**
 * Protocol owner for group epoch invalidation and Sender Key coverage.
 *
 * This class deliberately has no Android, Room, WorkManager, HTTP singleton, or
 * application-singleton dependency. Platform wiring supplies those capabilities.
 */
class GroupMessagingCoordinator(
    private val currentSession: () -> GroupMessagingSession?,
    private val invalidatePreparedEpoch: suspend (
        ownerUserId: String,
        conversationId: String,
        newRevision: Long?,
        now: Long,
    ) -> Unit,
    private val invalidateLocalSenderKey: (String) -> Unit,
    private val reconcileAttachments: suspend (conversationId: String, ownerUserId: String) -> Unit,
    private val ensureCoverageNow: suspend (conversationId: String, epoch: Long) -> Unit,
    private val redistributeCoverageNow: suspend (conversationId: String) -> Boolean,
    private val fetchCoverageStatus: suspend (
        authToken: String,
        conversationId: String,
        epoch: Long?,
        deviceId: Int,
    ) -> SenderKeyDistributionStatusDto,
    private val hasLocalSenderKeyMaterial: (conversationId: String, epoch: Long) -> Boolean,
    private val enqueueCoverageRetryCommand: suspend (
        conversationId: String,
        epoch: Long,
        reason: String,
    ) -> Unit,
    private val onAttachmentReconciliationFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun invalidateSenderKey(
        chatId: String,
        ownerUserId: String = currentSession()?.ownerUserId.orEmpty(),
        newRevision: Long? = null,
    ) {
        if (chatId.isBlank() || ownerUserId.isBlank()) return
        if (!isCurrentOwner(ownerUserId)) return

        invalidatePreparedEpoch(
            ownerUserId,
            chatId,
            newRevision?.takeIf { it > 0L },
            clock(),
        )
        // Durable rows are owner-scoped. Never touch the now-current account's
        // Signal store if the account changed while Room was writing.
        if (!isCurrentOwner(ownerUserId)) return
        invalidateLocalSenderKey(chatId)
        if (!isCurrentOwner(ownerUserId)) return
        try {
            reconcileAttachments(chatId, ownerUserId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onAttachmentReconciliationFailure(chatId, error)
        }
    }

    suspend fun ensureSenderKeyCoverage(
        chatId: String,
        epoch: Long,
    ): Result<SenderKeyDistributionStatusDto?> = coverageResult(chatId, epoch) { session ->
        ensureCoverageNow(chatId, epoch)
        fetchCoverageStatusForOwner(session.ownerUserId, chatId, epoch)
    }

    suspend fun redistributeNow(
        chatId: String,
        epoch: Long,
    ): Result<SenderKeyDistributionStatusDto?> = coverageResult(chatId, epoch) { session ->
        if (!redistributeCoverageNow(chatId)) {
            ensureCoverageNow(chatId, epoch)
        }
        fetchCoverageStatusForOwner(session.ownerUserId, chatId, epoch)
    }

    suspend fun hasLocalSenderKey(chatId: String, epoch: Long): Boolean =
        chatId.isNotBlank() && epoch > 0L && hasLocalSenderKeyMaterial(chatId, epoch)

    suspend fun getSenderKeyCoverageStatus(
        chatId: String,
        epoch: Long? = null,
    ): Result<SenderKeyDistributionStatusDto> {
        if (chatId.isBlank()) {
            return Result.failure(IllegalArgumentException("group_chat_id_missing"))
        }
        val session = sessionOrFailure().getOrElse { return Result.failure(it) }
        return try {
            Result.success(fetchCoverageStatusForOwner(session.ownerUserId, chatId, epoch))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun enqueueCoverageRetry(chatId: String, epoch: Long, reason: String) {
        if (chatId.isBlank() || epoch <= 0L || reason.isBlank() || sessionOrFailure().isFailure) return
        enqueueCoverageRetryCommand(chatId, epoch, reason)
    }

    private suspend fun coverageResult(
        chatId: String,
        epoch: Long,
        operation: suspend (GroupMessagingSession) -> SenderKeyDistributionStatusDto,
    ): Result<SenderKeyDistributionStatusDto?> {
        if (chatId.isBlank() || epoch <= 0L) {
            return Result.failure(IllegalArgumentException("group_epoch_unknown"))
        }
        val session = sessionOrFailure().getOrElse { return Result.failure(it) }
        return try {
            Result.success(operation(session))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun fetchCoverageStatusForOwner(
        ownerUserId: String,
        chatId: String,
        epoch: Long?,
    ): SenderKeyDistributionStatusDto {
        val live = liveSession(ownerUserId)
        val status = fetchCoverageStatus(live.authToken, chatId, epoch, live.deviceId)
        liveSession(ownerUserId)
        return status
    }

    private fun sessionOrFailure(): Result<GroupMessagingSession> {
        val session = currentSession()
        return if (
            session == null || session.ownerUserId.isBlank() ||
            session.authToken.isBlank() || session.deviceId <= 0
        ) {
            Result.failure(IllegalStateException("sender_key_session_missing"))
        } else {
            Result.success(session)
        }
    }

    private fun liveSession(ownerUserId: String): GroupMessagingSession {
        val session = sessionOrFailure().getOrElse {
            throw CancellationException("sender_key_session_changed")
        }
        if (session.ownerUserId != ownerUserId) {
            throw CancellationException("sender_key_session_changed")
        }
        return session
    }

    private fun isCurrentOwner(ownerUserId: String): Boolean =
        currentSession()?.ownerUserId == ownerUserId
}
