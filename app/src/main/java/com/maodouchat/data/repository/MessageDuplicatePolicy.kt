package com.maodouchat.data.repository

import com.maodouchat.data.model.Message

/**
 * 本地消息去重：同 id 无变化则跳过写入；不同 id 但同一投递（同会话/发送者/时间戳/正文）
 * 视为重复行，合并到已有主键，避免 v2 Inbox 与乐观发送落成两条。
 */
object MessageDuplicatePolicy {
    fun isRedundantWrite(existing: Message, merged: Message): Boolean {
        return existing.id == merged.id &&
            existing.chatId == merged.chatId &&
            existing.senderId == merged.senderId &&
            existing.content == merged.content &&
            existing.type == merged.type &&
            existing.timestamp == merged.timestamp &&
            existing.status == merged.status &&
            existing.editedAt == merged.editedAt &&
            existing.starred == merged.starred &&
            existing.reactions == merged.reactions &&
            existing.expiresAt == merged.expiresAt &&
            existing.sealedSender == merged.sealedSender
    }

    fun isSameDelivery(existing: Message, incoming: Message): Boolean {
        if (existing.chatId != incoming.chatId) return false
        if (existing.senderId != incoming.senderId) return false
        if (existing.timestamp != incoming.timestamp) return false
        if (existing.type != incoming.type) return false
        return existing.parsedContent() == incoming.parsedContent()
    }

    fun pickCanonical(existing: Message, incoming: Message): Message {
        // 已入库行保留主键，避免再插一条；状态/正文由 mergeMessageForPersistence 合并。
        return incoming.copy(id = existing.id)
    }
}
