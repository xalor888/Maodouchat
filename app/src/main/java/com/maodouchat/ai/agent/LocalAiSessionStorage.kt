package com.maodouchat.ai.agent

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

// 会话簇：最近会话列表的读写与 JSON 编解码。原 LocalAiProviderStore 的会话部分整体搬入。
internal object LocalAiSessionStorage {
    fun loadSessions(context: Context): List<AgentSession> {
        val raw = LocalAiStorePrefs.prefs(context)
            ?.getString(LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS), "[]").orEmpty()
        return parseSessions(raw)
    }

    fun saveSessions(context: Context, sessions: List<AgentSession>) {
        LocalAiStorePrefs.prefs(context)?.edit {
            putString(
                LocalAiStorePrefs.scoped(context, LocalAiStorePrefs.KEY_SESSIONS),
                encodeSessions(sessions.takeLast(12))
            )
        }
    }

    internal fun parseSessions(raw: String): List<AgentSession> {
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
                        title = o.optString("title").ifBlank { "新对话" },
                        createdAt = o.optLong("createdAt"),
                        messages = messages
                    )
                )
            }
        }
    }

    internal fun encodeSessions(list: List<AgentSession>): String {
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
