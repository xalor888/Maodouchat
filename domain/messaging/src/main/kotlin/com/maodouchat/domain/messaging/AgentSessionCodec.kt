package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.AgentChatMessage
import com.maodouchat.domain.messaging.AgentSession
import org.json.JSONArray
import org.json.JSONObject

// Agent 会话 JSON 编解码与保留上限，自 app 侧 ai/agent 存储簇收敛；
// prefs 读写仍留在 app 侧，只迁编解码与保留规则本身。
object AgentSessionCodec {

    const val MAX_STORED_SESSIONS: Int = 12

    fun retainRecent(sessions: List<AgentSession>): List<AgentSession> =
        sessions.takeLast(MAX_STORED_SESSIONS)

    fun parseSessions(raw: String): List<AgentSession> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                val msgs = o.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (j in 0 until msgs.length()) {
                        val m = msgs.optJSONObject(j) ?: continue
                        add(
                            AgentChatMessage(
                                role = m.optString("role"),
                                content = m.optString("content"),
                                toolCallId = m.optString("toolCallId").takeIf { it.isNotBlank() },
                                toolName = m.optString("toolName").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                add(
                    AgentSession(
                        id = id,
                        title = o.optString("title").ifBlank { AgentSessionTitlePolicy.DEFAULT_TITLE },
                        createdAt = o.optLong("createdAt"),
                        messages = messages
                    )
                )
            }
        }
    }

    fun encodeSessions(list: List<AgentSession>): String {
        val array = JSONArray()
        list.forEach { s ->
            val msgs = JSONArray()
            s.messages.filter { it.role != "tool" || it.content.isNotBlank() }.forEach { m ->
                msgs.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("toolCallId", m.toolCallId.orEmpty())
                        .put("toolName", m.toolName.orEmpty())
                )
            }
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAt", s.createdAt)
                    .put("messages", msgs)
            )
        }
        return array.toString()
    }
}
