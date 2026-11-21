package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentMissedCallLinePolicy
import com.maodouchat.domain.messaging.AgentReadEmptyPolicy
import com.maodouchat.domain.messaging.AgentTaskReadLimitPolicy
import kotlinx.coroutines.flow.first

// Agent 工具执行簇：未接来电只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskCallReads {
    internal suspend fun listMissedCalls(ports: AgentToolPorts, limit: Int): String {
        val secretPeers = AgentMessagingReadGates.secretPeerIds(ports)
        val calls = ports.database.missedCallDao().observeRecent().first()
            .filter { it.callerId !in secretPeers }
            .take(AgentTaskReadLimitPolicy.missedCallLimit(limit))
        if (calls.isEmpty()) return AgentReadEmptyPolicy.noMissedCalls()
        return calls.joinToString("\n") { call ->
            AgentMissedCallLinePolicy.missedCallLine(
                call.id, call.callerId, call.callerName, call.callType, call.receivedAt, call.isRead,
            )
        }
    }
}
