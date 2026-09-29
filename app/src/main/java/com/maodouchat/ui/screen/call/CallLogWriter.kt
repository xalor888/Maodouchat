package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.webrtc.CallType

/**
 * G372：通话记录写入（8.52 幂等回写 / 呼出占位改 ANSWERED / 时长口径 / 8.53 属主守卫）
 * 从 `CallViewModel` 抽出（纯搬移不改判断）。
 */
internal class CallLogWriter(
    private val application: Application,
    private val currentState: () -> CallUiState,
    private val activeCallId: () -> String,
    private val callLogOwnerUserId: () -> String,
) {
    fun writeCallLog(state: com.maodouchat.call.CallLogStore.State) {
        // 8.52：全量通话记录。同一 activeCallId 幂等回写（已接通话由 endCall 补时长）。
        val st = currentState()
        val callId = activeCallId()
        val peerId = st.contactId
        if (callId.isBlank() || peerId.isBlank()) return
        val existing = com.maodouchat.call.CallLogStore.list(application, peerId).firstOrNull { it.id == callId }
        // 呼出占位(MISSED)在接通瞬间被重写为 ANSWERED：startedAt 重置为接通时刻，
        // 使 duration = 纯通话时长（不含响铃）；呼入无占位，首次写入即 now，行为一致
        val startedAt = when {
            state == com.maodouchat.call.CallLogStore.State.ANSWERED && existing?.state == com.maodouchat.call.CallLogStore.State.MISSED ->
                System.currentTimeMillis()
            existing != null -> existing.startedAt
            else -> System.currentTimeMillis()
        }
        val durationMs = if (state == com.maodouchat.call.CallLogStore.State.ANSWERED && existing?.state == com.maodouchat.call.CallLogStore.State.ANSWERED) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else {
            existing?.durationMs ?: 0L
        }
        com.maodouchat.call.CallLogStore.upsert(
            application,
            com.maodouchat.call.CallLogStore.CallLogEntry(
                id = callId,
                peerId = peerId,
                peerName = st.contactName.ifBlank { peerId },
                isVideo = st.callType == CallType.VIDEO,
                direction = if (st.isIncoming) com.maodouchat.call.CallLogStore.Direction.INCOMING else com.maodouchat.call.CallLogStore.Direction.OUTGOING,
                state = state,
                startedAt = startedAt,
                durationMs = durationMs,
                isGroup = st.isGroupCall
            ),
            // 8.53：expectedUserId 守卫——8.55 改用通话开始时快照的 callLogOwnerUserId，
            // 通话中异地登出换号后旧通话不会写进新账号 key
            expectedUserId = callLogOwnerUserId().takeIf { it.isNotBlank() }
        )
    }
}
