package com.maodouchat.messaging.v2

import android.util.Log
import com.maodouchat.MaodouchatApp
import com.maodouchat.attachment.AttachmentTransferScheduler
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager

internal fun createAndroidGroupMessagingCoordinator(
    app: MaodouchatApp,
    signalProtocol: SignalProtocol,
    tokenManager: TokenManager,
): GroupMessagingCoordinator = GroupMessagingCoordinator(
    currentSession = {
        val owner = tokenManager.getUserId().orEmpty()
        val token = tokenManager.getToken().orEmpty()
        if (owner.isBlank() || token.isBlank()) null else {
            GroupMessagingSession(owner, token, signalProtocol.getDeviceId())
        }
    },
    invalidatePreparedEpoch = { ownerUserId, conversationId, newRevision, now ->
        app.database.messagingV2Dao().invalidateGroupEpoch(
            ownerUserId = ownerUserId,
            conversationId = conversationId,
            newRevision = newRevision,
            now = now,
        )
    },
    invalidateLocalSenderKey = { signalProtocol.invalidateGroupSenderKey(it) },
    reconcileAttachments = { conversationId, ownerUserId ->
        val dao = app.database.attachmentTransferDao()
        dao.clearWireContentForChat(conversationId, ownerUserId = ownerUserId)
        dao.getByChat(conversationId, ownerUserId = ownerUserId)
            .filter { it.state == AttachmentTransferState.READY && it.hasCompletedUpload() }
            .forEach {
                AttachmentTransferScheduler.schedule(
                    app,
                    it.messageId,
                    it.ownerUserId,
                    replace = true,
                )
            }
    },
    ensureCoverageNow = { conversationId, epoch ->
        app.senderKeyRetryManager.ensureCoverageNow(conversationId, epoch).getOrThrow()
    },
    redistributeCoverageNow = app.senderKeyRetryManager::redistributeNow,
    fetchCoverageStatus = { authToken, conversationId, epoch, deviceId ->
        ApiService.getSenderKeyDistributionStatus(
            authToken,
            conversationId,
            epoch,
            deviceId,
        ).getOrThrow()
    },
    hasLocalSenderKeyMaterial = signalProtocol::hasGroupDistributionId,
    enqueueCoverageRetryCommand = { conversationId, epoch, reason ->
        app.senderKeyRetryManager.enqueue(conversationId, epoch, reason)
    },
    onAttachmentReconciliationFailure = { conversationId, error ->
        Log.w(
            "GroupMessagingCoordinator",
            "attachment epoch reconciliation failed for $conversationId",
            error,
        )
    },
)
