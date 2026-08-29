package com.maodouchat.messaging.v2

import com.maodouchat.network.SenderKeyDistributionStatusDto
import kotlinx.coroutines.CancellationException

sealed interface GroupSenderKeyMaintenanceOutcome {
    data object Skipped : GroupSenderKeyMaintenanceOutcome

    data class Ready(
        val status: SenderKeyDistributionStatusDto?,
        val localHasSenderKey: Boolean,
    ) : GroupSenderKeyMaintenanceOutcome

    data class Pending(
        val status: SenderKeyDistributionStatusDto?,
        val localHasSenderKey: Boolean,
        val error: Throwable,
        val retryQueueError: Throwable? = null,
    ) : GroupSenderKeyMaintenanceOutcome

    data class Failed(
        val error: Throwable,
        val retryQueueError: Throwable? = null,
    ) : GroupSenderKeyMaintenanceOutcome
}

/** Owns manual/automatic Sender Key coverage retries without UI-held epoch state. */
class GroupSenderKeyMaintenanceCoordinator(
    private val ensureCoverage: suspend (conversationId: String, epoch: Long) -> Result<SenderKeyDistributionStatusDto?>,
    private val redistribute: suspend (conversationId: String, epoch: Long) -> Result<SenderKeyDistributionStatusDto?>,
    private val hasLocalSenderKey: suspend (conversationId: String, epoch: Long) -> Boolean,
    private val enqueueRetry: suspend (conversationId: String, epoch: Long, reason: String) -> Unit,
) {
    private val inFlightEpochs = mutableSetOf<Long>()

    suspend fun runAutomatic(
        conversationId: String,
        epoch: Long,
        currentStatus: SenderKeyDistributionStatusDto?,
        localHasSenderKeyHint: Boolean?,
    ): GroupSenderKeyMaintenanceOutcome {
        if (conversationId.isBlank() || epoch <= 0L) return GroupSenderKeyMaintenanceOutcome.Skipped
        if (coverageComplete(currentStatus, epoch, localHasSenderKeyHint == true)) {
            return GroupSenderKeyMaintenanceOutcome.Skipped
        }
        if (!claim(epoch)) return GroupSenderKeyMaintenanceOutcome.Skipped
        return try {
            evaluate(conversationId, epoch, "auto", ensureCoverage(conversationId, epoch))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(conversationId, epoch, "auto_failed", error)
        } finally {
            release(epoch)
        }
    }

    suspend fun runManual(
        conversationId: String,
        epoch: Long,
    ): GroupSenderKeyMaintenanceOutcome {
        if (conversationId.isBlank() || epoch <= 0L) {
            return GroupSenderKeyMaintenanceOutcome.Failed(
                IllegalArgumentException("group_epoch_unknown"),
            )
        }
        return try {
            evaluate(conversationId, epoch, "manual", redistribute(conversationId, epoch))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed(conversationId, epoch, "manual_failed", error)
        }
    }

    private suspend fun evaluate(
        conversationId: String,
        epoch: Long,
        reasonPrefix: String,
        result: Result<SenderKeyDistributionStatusDto?>,
    ): GroupSenderKeyMaintenanceOutcome {
        val error = result.exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) throw error
            return failed(conversationId, epoch, "${reasonPrefix}_failed", error)
        }
        val status = result.getOrNull()
        val localHasKey = hasLocalSenderKey(conversationId, epoch)
        if (coverageComplete(status, epoch, localHasKey)) {
            return GroupSenderKeyMaintenanceOutcome.Ready(status, localHasKey)
        }
        val pending = IllegalStateException("sender_key_coverage_incomplete")
        return GroupSenderKeyMaintenanceOutcome.Pending(
            status = status,
            localHasSenderKey = localHasKey,
            error = pending,
            retryQueueError = enqueueRetrySafely(
                conversationId,
                epoch,
                "${reasonPrefix}_incomplete",
            ),
        )
    }

    private suspend fun failed(
        conversationId: String,
        epoch: Long,
        reason: String,
        error: Throwable,
    ): GroupSenderKeyMaintenanceOutcome.Failed = GroupSenderKeyMaintenanceOutcome.Failed(
        error = error,
        retryQueueError = enqueueRetrySafely(
            conversationId,
            epoch,
            "$reason:${error.message.orEmpty()}",
        ),
    )

    private suspend fun enqueueRetrySafely(
        conversationId: String,
        epoch: Long,
        reason: String,
    ): Throwable? = try {
        enqueueRetry(conversationId, epoch, reason)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error
    }

    private fun claim(epoch: Long): Boolean = synchronized(inFlightEpochs) {
        inFlightEpochs.add(epoch)
    }

    private fun release(epoch: Long) {
        synchronized(inFlightEpochs) { inFlightEpochs.remove(epoch) }
    }

    companion object {
        internal fun coverageComplete(
            status: SenderKeyDistributionStatusDto?,
            expectedEpoch: Long,
            localHasSenderKey: Boolean,
        ): Boolean {
            if (!localHasSenderKey || status == null || status.epoch < expectedEpoch) return false
            if (status.total == 0) return true
            return status.pending == 0 && status.failed == 0 && status.sent >= status.total
        }
    }
}
