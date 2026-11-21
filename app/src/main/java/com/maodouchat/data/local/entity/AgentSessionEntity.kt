package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.maodouchat.domain.messaging.AgentChatMessage
import com.maodouchat.domain.messaging.AgentSession
import com.maodouchat.domain.messaging.AgentSessionCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 会话（退役 `maodou_local_ai-<userId>` 加密 prefs 里的 sessions_json）。
 * 会话头按列存；消息体是变长列表，沿用 AgentSessionCodec 的消息 JSON 形状
 * 存一列（role/content/toolCallId/toolName），与旧 JSON 逐字段一致。
 * sortOrder 存旧 JSON 数组顺序（新会话追加在最后，保留上限 12 条时取最后 12 条）。
 */
@Entity(
    tableName = "agent_sessions",
    primaryKeys = ["ownerUserId", "id"],
    indices = [
        Index(value = ["ownerUserId"]),
    ],
)
data class AgentSessionEntity(
    val ownerUserId: String,
    val id: String,
    val title: String,
    val createdAt: Long,
    val messagesJson: String,
    val sortOrder: Int,
)

fun AgentSession.toEntity(ownerUserId: String, sortOrder: Int): AgentSessionEntity =
    AgentSessionEntity(
        ownerUserId = ownerUserId,
        id = id,
        title = title,
        createdAt = createdAt,
        messagesJson = encodeAgentMessages(messages),
        sortOrder = sortOrder,
    )

fun AgentSessionEntity.toModel(): AgentSession? = runCatching {
    val sessionJson = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("createdAt", createdAt)
        .put("messages", JSONArray(messagesJson))
    AgentSessionCodec.parseSessions("[$sessionJson]").singleOrNull()
}.getOrNull()

/** 消息数组编码，与 AgentSessionCodec.encodeSessions 里的消息循环逐字段一致。 */
internal fun encodeAgentMessages(messages: List<AgentChatMessage>): String {
    val array = JSONArray()
    messages.filter { it.role != "tool" || it.content.isNotBlank() }.forEach { m ->
        array.put(
            JSONObject()
                .put("role", m.role)
                .put("content", m.content)
                .put("toolCallId", m.toolCallId.orEmpty())
                .put("toolName", m.toolName.orEmpty())
        )
    }
    return array.toString()
}
