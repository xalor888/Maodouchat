package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallLogEntry
import com.maodouchat.core.model.CallLogRow
import com.maodouchat.core.model.Direction
import com.maodouchat.core.model.MissedCall
import com.maodouchat.core.model.State

// 未接来电行：本地通话记录优先、Room 未接补齐，同 id 去重后按时间倒序；
// CallLogStore 优先是 8.53 的竞态处理：接听瞬间对端挂断时，已接记录不被 Room 的 MISSED 覆盖。
// 自 ChatListFolderDialogs 的 callLogRows 收敛。
object MissedCallRowsPolicy {

    fun mergeRows(
        storeLog: List<CallLogEntry>,
        missedCalls: List<MissedCall>
    ): List<CallLogRow> {
        val seen = HashSet<String>()
        return buildList {
            storeLog.forEach { e ->
                if (seen.add(e.id)) {
                    add(
                        CallLogRow(
                            id = e.id,
                            peerId = e.peerId,
                            peerName = e.peerName,
                            video = e.isVideo,
                            direction = e.direction,
                            state = e.state,
                            at = e.startedAt,
                            durationMs = e.durationMs
                        )
                    )
                }
            }
            missedCalls.forEach { mc ->
                if (seen.add(mc.id)) {
                    add(
                        CallLogRow(
                            id = mc.id,
                            peerId = mc.callerId,
                            peerName = mc.callerName,
                            video = mc.callType.equals("VIDEO", ignoreCase = true),
                            direction = Direction.INCOMING,
                            state = State.MISSED,
                            at = mc.receivedAt,
                            durationMs = 0L
                        )
                    )
                }
            }
        }.sortedByDescending { it.at }
    }
}
