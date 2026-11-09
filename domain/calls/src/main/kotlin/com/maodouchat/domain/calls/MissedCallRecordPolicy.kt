package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.MissedCall
import com.maodouchat.core.model.State

/** 未接来电一次超时记录要同时写下的两条记录。 */
data class MissedCallRecord(
    val missedCall: MissedCall,
    val callLogEntry: CallLogEntry,
)

/** 空白来电显示名回落为对方 id。 */
fun displayCallerName(callerName: String, fromUserId: String): String =
    callerName.ifBlank { fromUserId }

/** 未接来电记录组装：Room 的 MissedCall 与全量通话记录的 CallLogEntry 共用同一 id 与显示名。 */
fun buildMissedCallRecord(
    missedId: String,
    fromUserId: String,
    callerName: String,
    isVideo: Boolean,
    nowMs: Long,
    isGroup: Boolean = false,
): MissedCallRecord {
    val name = displayCallerName(callerName, fromUserId)
    return MissedCallRecord(
        missedCall = MissedCall(
            id = missedId,
            callerId = fromUserId,
            callerName = name,
            callType = if (isVideo) "VIDEO" else "AUDIO",
            receivedAt = nowMs,
        ),
        callLogEntry = CallLogEntry(
            id = missedId,
            peerId = fromUserId,
            peerName = name,
            isVideo = isVideo,
            direction = Direction.INCOMING,
            state = State.MISSED,
            startedAt = nowMs,
            isGroup = isGroup,
        ),
    )
}
