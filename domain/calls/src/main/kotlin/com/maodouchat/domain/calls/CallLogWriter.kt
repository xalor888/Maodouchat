package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.State

/**
 * G372：通话记录写入（8.52 幂等回写 / 呼出占位改 ANSWERED / 时长口径 / 8.53 属主守卫）
 * 从 `CallViewModel` 抽出（纯搬移不改判断）。读写走 listCallLogs / upsertCallLog 端口
 *（app 侧 CallLogStore，需 Context/SharedPreferences）。
 */
class CallLogWriter(
    private val listCallLogs: (peerId: String) -> List<CallLogEntry>,
    private val upsertCallLog: (entry: CallLogEntry, expectedUserId: String?) -> Unit,
    private val currentState: () -> CallUiState,
    private val activeCallId: () -> String,
    private val callLogOwnerUserId: () -> String,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun writeCallLog(state: State) {
        // 8.52：全量通话记录。同一 activeCallId 幂等回写（已接通话由 endCall 补时长）。
        val st = currentState()
        val callId = activeCallId()
        val peerId = st.contactId
        if (callId.isBlank() || peerId.isBlank()) return
        val existing = listCallLogs(peerId).firstOrNull { it.id == callId }
        // 呼出占位(MISSED)在接通瞬间被重写为 ANSWERED：startedAt 重置为接通时刻，
        // 使 duration = 纯通话时长（不含响铃）；呼入无占位，首次写入即 now，行为一致
        val startedAt = when {
            state == State.ANSWERED && existing?.state == State.MISSED ->
                nowMillis()
            existing != null -> existing.startedAt
            else -> nowMillis()
        }
        val durationMs = if (state == State.ANSWERED && existing?.state == State.ANSWERED) {
            (nowMillis() - startedAt).coerceAtLeast(0L)
        } else {
            existing?.durationMs ?: 0L
        }
        upsertCallLog(
            CallLogEntry(
                id = callId,
                peerId = peerId,
                peerName = st.contactName.ifBlank { peerId },
                isVideo = st.callType == CallType.VIDEO,
                direction = if (st.isIncoming) Direction.INCOMING else Direction.OUTGOING,
                state = state,
                startedAt = startedAt,
                durationMs = durationMs,
                isGroup = st.isGroupCall
            ),
            // 8.53：expectedUserId 守卫——8.55 改用通话开始时快照的 callLogOwnerUserId，
            // 通话中异地登出换号后旧通话不会写进新账号 key
            callLogOwnerUserId().takeIf { it.isNotBlank() }
        )
    }
}
