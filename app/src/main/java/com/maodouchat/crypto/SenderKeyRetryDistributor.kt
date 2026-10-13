package com.maodouchat.crypto

import android.content.Context
import android.util.Log
import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.data.model.MessageType
import com.maodouchat.network.ApiService
import com.maodouchat.network.SenderKeyDistributionTargetDto
import com.maodouchat.network.TokenManager
import java.util.UUID

class SenderKeyRetryDistributor(
    private val retryDao: SenderKeyRetryDao,
    private val signalProtocol: SignalProtocol,
    private val tokenManager: TokenManager,
    private val context: Context?,
    private val currentUserId: () -> String?,
    private val setCurrentUserId: (String?) -> Unit,
    private val sessionActive: (String) -> Boolean
) {
    suspend fun redistribute(task: SenderKeyRetryEntity, token: String, expectedOwnerUserId: String): Long {
        if (expectedOwnerUserId.isBlank() || task.ownerUserId != expectedOwnerUserId ||
            !SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                token,
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        var liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
        val chat = ApiService.getChats(liveToken).getOrThrow().firstOrNull { it.id == task.chatId }
            ?: run {
                check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
                retryDao.delete(expectedOwnerUserId, task.chatId)
                return task.epoch
            }
        if (!chat.isGroup) {
            check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
            retryDao.delete(expectedOwnerUserId, task.chatId)
            return chat.memberRevision
        }
        val epoch = chat.memberRevision
        // Do not send coverage under a different epoch than the retry task expected —
        // that mis-labels server coverage rows and leaves encrypt path on a stale distribution.
        // epoch 0 任务/未知 revision 一律 fail closed，禁止静默用 live epoch 顶替
        if (task.epoch <= 0L || epoch <= 0L || epoch != task.epoch) {
            error("sender_key_epoch_changed:task=${task.epoch},live=$epoch")
        }
        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                tokenManager.getToken().orEmpty().ifBlank { liveToken },
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
        val members = ApiService.getGroupMembers(liveToken, task.chatId).getOrThrow()
        val recipientIds = members.map { it.userId }
            .filter { it.isNotBlank() && !com.maodouchat.bot.BotCommandPolicy.isBotUserId(it) }
            .distinct()
        // 始终先 mint 本地 distribution：单成员群 / 仅本机多设备也必须有本地 SK，
        // 否则 encrypt 路径 hasGroupDistributionId=false 会永久失败。
        val payload = signalProtocol.createGroupSenderKeyDistribution(task.chatId, epoch)
        val rawEnvelope = signalProtocol.buildSenderKeyDistributionEnvelope(
            task.chatId,
            payload.distributionId,
            payload.message,
            payload.epoch
        )
        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                tokenManager.getToken().orEmpty().ifBlank { liveToken },
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        liveToken = tokenManager.getToken().orEmpty().ifBlank { liveToken }
        val distribution = try {
            signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = liveToken,
                recipientIds = recipientIds,
                plaintext = rawEnvelope,
                payloadType = MessageType.SK_DIST.name,
                includeCurrentUserDevices = true
            ).getOrThrow()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: NoRecipientDevicesException) {
            // 9.3xx：群内还有其他成员、但他们的设备会话尚未建立时，绝不能把本 epoch 视为
            // "覆盖完成"——否则这些成员永远拿不到 Sender Key（用户侧表现为"缺少 senderKey"、
            // 群消息永远解密失败）。只有真正没有任何外部收件人（单人/仅自己设备）时才允许
            // 本地-only 覆盖完成；其余情况抛出并按退避重试（外层 catch 记失败）。
            val hasOtherRecipients = recipientIds.any { it.isNotBlank() && it != expectedOwnerUserId }
            if (!hasOtherRecipients) return epoch
            throw error
        }
        if (distribution.targets.isEmpty()) {
            // 本地 mint 成功且无 peer/其它设备目标：本地-only 覆盖
            return epoch
        }

        if (!SenderKeyRetrySessionPolicy.mayContinueBatch(
                expectedOwnerUserId,
                tokenManager.getToken().orEmpty().ifBlank { liveToken },
                tokenManager.getUserId(),
            )
        ) {
            error("sender_key_session_changed")
        }
        val messageId = "sk_${UUID.randomUUID()}"
        val app = context as? com.maodouchat.MaodouchatApp
            ?: throw IllegalStateException("sender_key_runtime_unavailable")
        app.messagingV2Outbox.enqueueSenderKeyDistribution(
            conversationId = task.chatId,
            distributionEnvelope = rawEnvelope,
            groupRevision = epoch,
            messageId = messageId,
        )
        check(sessionActive(expectedOwnerUserId)) { "sender_key_session_changed" }
        // The durable outbox item must reach the server before coverage can be considered
        // complete. Returning success here lets a foreground data message race ahead of SKDM.
        throw SenderKeyCoveragePendingException(task.chatId, epoch)
    }

    /** Coverage GET is evaluated against the committed v2 Sender Key mailbox envelopes. */
    suspend fun verifyCoverageComplete(token: String, chatId: String, epoch: Long, expectedOwnerUserId: String): Boolean {
        if (epoch <= 0L) return false
        val coverageResult = ApiService.getSenderKeyDistributionStatus(
            token = token,
            chatId = chatId,
            epoch = epoch,
            currentDeviceId = signalProtocol.getDeviceId()
        )
        coverageResult.exceptionOrNull()?.let { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
        if (!sessionActive(expectedOwnerUserId)) return false
        val coverage = coverageResult.getOrNull() ?: return false
        return !SenderKeyCoveragePolicy.requiresDistribution(
            hasLocalDistribution = signalProtocol.groupDistributionUsable(chatId, epoch),
            requestedEpoch = epoch,
            statusEpoch = coverage.epoch,
            targetStatuses = coverage.targets.map(SenderKeyDistributionTargetDto::status)
        )
    }

    suspend fun clearAttachmentWireForChat(chatId: String) {
        val app = context as? com.maodouchat.MaodouchatApp ?: return
        try {
            val dao = app.database.attachmentTransferDao()
            val ownerUserId = currentUserId() ?: return
            dao.clearWireContentForChat(chatId, ownerUserId = ownerUserId)
            dao.getByChat(chatId, ownerUserId = ownerUserId)
                .filter { it.state == AttachmentTransferState.READY && it.hasCompletedUpload() }
                .forEach {
                    com.maodouchat.attachment.AttachmentTransferScheduler.schedule(
                        app,
                        it.messageId,
                        ownerUserId,
                        replace = true
                    )
                }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "clearAttachmentWireForChat failed for $chatId", error)
        }
    }

    suspend fun ensureSignalReady(token: String, userId: String) {
        if (currentUserId() == userId && signalProtocol.isLocalCryptoReadyFor(userId)) return
        if (!signalProtocol.ensureLocalCryptoReady(token, userId)) {
            setCurrentUserId(null)
            error("Signal protocol not ready for sender-key retry")
        }
        setCurrentUserId(userId)
    }

    companion object {
        private const val TAG = "SenderKeyRetryDistributor"
    }
}
