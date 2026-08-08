package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.MessageReaction

/**
 * Buffer MESSAGE_REACTION_UPDATED when the target message is not yet in Room
 * (reaction WS can race ahead of MessageReceived / history sync).
 *
 * Entries expire after [DEFAULT_TTL_MS] so a permanently missing id cannot grow unbounded.
 */
object PendingReactionPolicy {

    const val DEFAULT_TTL_MS: Long = 150_000L
    const val DEFAULT_MAX_ENTRIES: Int = 200

    data class Entry(
        val chatId: String,
        val messageId: String,
        val reactions: List<MessageReaction>,
        val receivedAt: Long
    )

    data class ApplyResult(
        val pending: Map<String, Entry>,
        /** Entries whose message is now present and should be written to Room. */
        val ready: List<Entry>
    )

    fun bufferKey(chatId: String, messageId: String): String = "$chatId\u0000$messageId"

    fun put(
        pending: Map<String, Entry>,
        chatId: String,
        messageId: String,
        reactions: List<MessageReaction>,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxEntries: Int = DEFAULT_MAX_ENTRIES
    ): Map<String, Entry> {
        if (chatId.isBlank() || messageId.isBlank()) return prune(pending, nowMs, ttlMs, maxEntries)
        val key = bufferKey(chatId, messageId)
        val next = pending.toMutableMap()
        next[key] = Entry(
            chatId = chatId,
            messageId = messageId,
            reactions = reactions,
            receivedAt = nowMs
        )
        return prune(next, nowMs, ttlMs, maxEntries)
    }

    /**
     * After a message lands in Room, pull matching buffered reactions (if any)
     * and drop them from the map.
     */
    fun takeForMessage(
        pending: Map<String, Entry>,
        chatId: String,
        messageId: String,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxEntries: Int = DEFAULT_MAX_ENTRIES
    ): ApplyResult {
        if (chatId.isBlank() || messageId.isBlank()) {
            return ApplyResult(prune(pending, nowMs, ttlMs, maxEntries), emptyList())
        }
        val key = bufferKey(chatId, messageId)
        val pruned = prune(pending, nowMs, ttlMs, maxEntries).toMutableMap()
        val entry = pruned.remove(key)
        return ApplyResult(
            pending = pruned,
            ready = listOfNotNull(entry?.takeIf { it.chatId == chatId && it.messageId == messageId })
        )
    }

    fun prune(
        pending: Map<String, Entry>,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxEntries: Int = DEFAULT_MAX_ENTRIES
    ): Map<String, Entry> {
        if (pending.isEmpty()) return pending
        val alive = pending.filterValues { nowMs - it.receivedAt <= ttlMs }
        if (alive.size <= maxEntries) return alive
        return alive.entries
            .sortedByDescending { it.value.receivedAt }
            .take(maxEntries)
            .associate { it.key to it.value }
    }
}
