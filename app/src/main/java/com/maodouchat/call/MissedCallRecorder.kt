package com.maodouchat.call

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.MissedCall
import com.maodouchat.data.repository.MissedCallRepository
import com.maodouchat.network.TokenManager
import com.maodouchat.notification.NotificationPreferences
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.util.AppNotifier

/**
 * Shared local-timeout → missed-call write used by [IncomingCallObserver] and
 * [com.maodouchat.ui.screen.call.CallViewModel] ring timeout. Stable [callId] makes
 * REPLACE insert + tray notify id idempotent if both paths race.
 */
object MissedCallRecorder {

    suspend fun recordRingTimeout(
        context: Context,
        signalingCallId: String,
        fromUserId: String,
        callerName: String,
        isVideo: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        isGroup: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        // Logout / account switch race: do not write missed rows or tray for a dead session.
        val tokenManager = TokenManager.getInstance(appCtx)
        val userId = tokenManager.getUserId().orEmpty()
        val token = tokenManager.getToken()
        if (!BackgroundSessionGate.mayContinue(userId, token, userId)) {
            if (signalingCallId.isNotBlank()) {
                AppNotifier.cancelIncomingCall(appCtx, signalingCallId)
            }
            return
        }
        val missedId = MissedCallTimeoutPolicy.missedRecordId(
            signalingCallId = signalingCallId,
            fromUserId = fromUserId,
            nowMs = nowMs,
        )
        // Incoming FCM uses callId.hashCode(); cancel before posting missed so shade
        // does not keep a ringing "encrypted call" next to missed.
        if (signalingCallId.isNotBlank()) {
            AppNotifier.cancelIncomingCall(appCtx, signalingCallId)
        }
        val repo = MissedCallRepository(
            (appCtx as? MaodouchatApp)?.database?.missedCallDao()
                ?: return
        )
        // Re-check after suspend points in Room path setup (token clear mid-call).
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = userId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            return
        }
        repo.insert(
            MissedCall(
                id = missedId,
                callerId = fromUserId,
                callerName = callerName.ifBlank { fromUserId },
                callType = if (isVideo) "VIDEO" else "AUDIO",
                receivedAt = nowMs,
            )
        )
        // 8.52：同步写全量通话记录（未接来电入 CallLogStore）；
        // 传呼入时快照的 userId 作守卫，防 repo.insert 挂起点后换号写错账号
        CallLogStore.upsert(
            appCtx,
            CallLogStore.CallLogEntry(
                id = missedId,
                peerId = fromUserId,
                peerName = callerName.ifBlank { fromUserId },
                isVideo = isVideo,
                direction = CallLogStore.Direction.INCOMING,
                state = CallLogStore.State.MISSED,
                startedAt = nowMs,
                isGroup = isGroup
            ),
            expectedUserId = userId
        )
        if (!BackgroundSessionGate.mayContinue(
                expectedUserId = userId,
                liveToken = tokenManager.getToken(),
                liveUserId = tokenManager.getUserId(),
            )
        ) {
            repo.delete(missedId)
            return
        }
        if (MissedCallTimeoutPolicy.shouldShowTray(NotificationPreferences.notificationsEnabled(appCtx))) {
            // Tray prefs are account-scoped; skip if session already gone.
            if (!BackgroundSessionGate.mayContinue(
                    expectedUserId = userId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return
            }
            AppNotifier.showMissedCall(
                appCtx,
                missedId,
                callerName.ifBlank { fromUserId },
                isVideo,
                expectedUserId = userId,
            )
        }
    }
}
