package com.maodouchat.core.model

import com.maodouchat.core.model.ChatTextDisplayPolicy
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val meta: MessageMeta = MessageMeta(),
    val reactions: List<MessageReaction> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false
) {
    /** 解包 content 里的 <meta>{...}</meta> 标签。
     * 9.143：用 lastIndexOf——正文可能含字面 "<meta>"（用户输入），真实 meta 块恒由
     * composeContentWithMeta 追加在末尾；此前 substringBefore 取首个出现位置，
     * 含该字面量的消息会被截断显示（正文后段丢失）。 */
    fun parsedContent(): String = content.lastIndexOf(META_TAG_PREFIX).let { idx ->
        val body = if (idx < 0) content else content.substring(0, idx)
        ChatTextDisplayPolicy.unescapeHtmlEntities(body)
    }

    fun parsedMeta(): MessageMeta {
        val idx = content.lastIndexOf(META_TAG_PREFIX)
        if (idx < 0) return meta
        val json = content.substring(idx + META_TAG_PREFIX.length).substringBefore("</meta>")
        return runCatching {
            MessageMetaJson.fromJsonString(json)
        }.getOrDefault(meta)
    }

    companion object {
        const val META_TAG_PREFIX = "<meta>"
    }
}
