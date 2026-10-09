package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.flow.first

// Agent 工具执行簇：未接来电只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskCallReads {
    internal suspend fun listMissedCalls(app: MaodouchatApp, limit: Int): String {
        val secretPeers = AgentMessagingReadGates.secretPeerIds(app)
        val calls = app.database.missedCallDao().observeRecent().first()
            .filter { it.callerId !in secretPeers }
            .take(limit.coerceIn(1, 30))
        if (calls.isEmpty()) return "No missed calls."
        return calls.joinToString("\n") { call ->
            "${call.id}\t${call.callerId}\t${call.callerName}\t${call.callType}\t${call.receivedAt}\tread=${call.isRead}"
        }
    }
}
