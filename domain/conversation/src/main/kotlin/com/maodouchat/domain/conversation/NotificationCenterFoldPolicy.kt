package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem

/**
 * Pure list transforms for the notification center.
 * NotificationCenterRepository keeps the shell (account gate, StateFlow, Room);
 * every list computation lives here so JVM tests can cover it.
 */
object NotificationCenterFoldPolicy {

    /** Max rows kept in the center; older rows drop off the tail on insert. */
    const val MAX_KEEP = 520

    fun foldInto(
        current: List<NotificationCenterItem>,
        item: NotificationCenterItem,
        maxKeep: Int = MAX_KEEP,
    ): List<NotificationCenterItem> {
        // 同主题条目按类型合并（如同一未接来电不重复叠加）
        val index = current.indexOfFirst {
            it.type == item.type && it.mergeKey == item.mergeKey && !it.read
        }
        val updated = if (index >= 0) {
            val existing = current[index]
            // FCM+WS same message, or ring-timeout+peer-hang-up same missed call:
            // do not double-count under a shared mergeKey.
            val skipCount = NotificationCenterMergePolicy
                .shouldSkipCountIncrement(
                    itemType = item.type,
                    existingId = existing.id,
                    existingExtra = existing.extra,
                    incomingId = item.id,
                    incomingExtra = item.extra,
                )
            val merged = existing.copy(
                count = if (skipCount) existing.count else existing.count + 1,
                updatedAt = item.updatedAt,
                title = item.title,
                preview = item.preview,
                subtitle = item.subtitle,
                deeplink = item.deeplink,
                // Keep latest messageId/callId so delete/revoke/cancel can still match the head.
                extra = item.extra,
                id = if (skipCount) existing.id else item.id,
            )
            current.toMutableList().apply { set(index, merged) }
        } else {
            (listOf(item) + current).take(maxKeep)
        }
        return updated
    }

    fun markAllRead(
        items: List<NotificationCenterItem>,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        return items.map { if (it.read) it else it.copy(read = true, updatedAt = nowMs) }
    }

    fun markRead(
        items: List<NotificationCenterItem>,
        itemId: String,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        return items.map {
            if (it.id == itemId && !it.read) it.copy(read = true, updatedAt = nowMs) else it
        }
    }

    fun markUnread(
        items: List<NotificationCenterItem>,
        itemId: String,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        return items.map {
            if (it.id == itemId && it.read) it.copy(read = false, updatedAt = nowMs) else it
        }
    }

    fun markChatMessagesRead(
        items: List<NotificationCenterItem>,
        chatId: String,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        if (chatId.isBlank()) return items
        return items.map { item ->
            val matches = NotificationCenterReadPolicy.isChatMessageItem(
                chatId = chatId,
                mergeKey = item.mergeKey,
                deeplink = item.deeplink,
                extraChatId = item.extra["chatId"]
            )
            if (matches && !item.read) item.copy(read = true, updatedAt = nowMs) else item
        }
    }

    fun markAiTasksRead(
        items: List<NotificationCenterItem>,
        chatId: String,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        if (chatId.isBlank()) return items
        return items.map { item ->
            val matches = item.type == "AI_TASK" &&
                (item.mergeKey == "ai_tasks_$chatId" ||
                    item.deeplink == "maodouchat:ai_tasks:$chatId" ||
                    item.extra["chatId"] == chatId)
            if (matches && !item.read) item.copy(read = true, updatedAt = nowMs) else item
        }
    }

    fun markPostInteractionsRead(
        items: List<NotificationCenterItem>,
        postId: String,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        if (postId.isBlank()) return items
        return items.map { item ->
            val matches = item.type == "POST_INTERACTION" &&
                (item.mergeKey == "post_$postId" || item.extra["postId"] == postId)
            if (matches && !item.read) item.copy(read = true, updatedAt = nowMs) else item
        }
    }

    fun removeChatItems(
        items: List<NotificationCenterItem>,
        chatId: String,
    ): List<NotificationCenterItem> {
        if (chatId.isBlank()) return items
        return items.filterNot { item ->
            NotificationCenterReadPolicy.belongsToChat(
                chatId = chatId,
                mergeKey = item.mergeKey,
                deeplink = item.deeplink,
                extraChatId = item.extra["chatId"]
            )
        }
    }

    fun removeMessageReferences(
        items: List<NotificationCenterItem>,
        messageId: String,
    ): List<NotificationCenterItem> {
        if (messageId.isBlank()) return items
        return items.filterNot { item ->
            NotificationCenterReadPolicy.referencesMessage(
                messageId = messageId,
                itemId = item.id,
                extraMessageId = item.extra["messageId"]
            )
        }
    }

    fun deleteItemsForMessages(
        items: List<NotificationCenterItem>,
        messageIds: List<String>,
    ): List<NotificationCenterItem> {
        if (messageIds.isEmpty()) return items
        val ids = messageIds.distinct().filter(String::isNotBlank)
        return items.filterNot { item ->
            ids.any { id ->
                NotificationCenterReadPolicy.referencesMessage(
                    messageId = id,
                    itemId = item.id,
                    extraMessageId = item.extra["messageId"]
                )
            }
        }
    }

    fun updateMessagePreview(
        items: List<NotificationCenterItem>,
        messageId: String,
        preview: String,
        nowMs: Long,
    ): List<NotificationCenterItem> {
        if (messageId.isBlank() || preview.isBlank()) return items
        return items.map { item ->
            val matches = NotificationCenterReadPolicy.referencesMessage(
                messageId = messageId,
                itemId = item.id,
                extraMessageId = item.extra["messageId"]
            )
            if (matches && item.preview != preview) item.copy(preview = preview, updatedAt = nowMs) else item
        }
    }
}
