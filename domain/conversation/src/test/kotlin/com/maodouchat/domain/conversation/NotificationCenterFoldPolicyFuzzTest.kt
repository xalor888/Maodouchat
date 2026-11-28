// NotificationCenterFoldPolicy fuzz (146th block). Oracle reimplements
// the eleven list transforms from production text, including the two
// already-fuzz-covered delegates it calls: NotificationCenterReadPolicy
// (isChatMessageItem/belongsToChat/referencesMessage, fuzz-106) and
// NotificationCenterMergePolicy.shouldSkipCountIncrement (fuzz-16).
// This block covers the FoldPolicy functions themselves, not the
// delegates. Items are plain data; createdAt/updatedAt are always
// passed explicitly, never System.currentTimeMillis().
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private fun isChatMessageItemOracle146(chatId: String, mergeKey: String, deeplink: String?, extraChatId: String?): Boolean {
    if (chatId.isBlank()) return false
    if (mergeKey == "msg_$chatId") return true
    if (deeplink == "maodouchat:chat:$chatId") return true
    if (extraChatId == chatId && mergeKey.startsWith("msg_")) return true
    return false
}

private fun belongsToChatOracle146(chatId: String, mergeKey: String, deeplink: String?, extraChatId: String?): Boolean {
    if (chatId.isBlank()) return false
    if (isChatMessageItemOracle146(chatId, mergeKey, deeplink, extraChatId)) return true
    if (mergeKey == "ai_tasks_$chatId") return true
    if (deeplink == "maodouchat:ai_tasks:$chatId") return true
    if (extraChatId == chatId) return true
    return false
}

private fun referencesMessageOracle146(messageId: String, itemId: String, extraMessageId: String?): Boolean {
    if (messageId.isBlank()) return false
    if (extraMessageId == messageId) return true
    if (itemId.startsWith("msg_")) {
        val idx = itemId.lastIndexOf("_m_")
        if (idx > 0) {
            val embedded = itemId.substring(idx + 1)
            if (embedded == messageId) return true
        }
    }
    return false
}

private fun isSameMessagePayloadOracle146(existingId: String, existingMessageId: String?, incomingId: String, incomingMessageId: String?): Boolean {
    val incoming = incomingMessageId?.takeIf(String::isNotBlank)
    if (incoming != null && existingMessageId == incoming) return true
    if (incomingId.isNotBlank() && existingId == incomingId) return true
    return false
}

private fun isSameMissedCallPayloadOracle146(existingId: String, existingCallId: String?, incomingId: String, incomingCallId: String?): Boolean {
    if (incomingId.isNotBlank() && existingId == incomingId) return true
    val incoming = incomingCallId?.takeIf(String::isNotBlank) ?: return false
    return existingCallId == incoming
}

private fun isSameStableExtraOracle146(existingValue: String?, incomingValue: String?): Boolean {
    val incoming = incomingValue?.takeIf(String::isNotBlank) ?: return false
    return existingValue == incoming
}

private fun shouldSkipCountIncrementOracle146(
    itemType: String,
    existingId: String,
    existingExtra: Map<String, String>,
    incomingId: String,
    incomingExtra: Map<String, String>,
): Boolean = when (itemType) {
    "MESSAGE" -> isSameMessagePayloadOracle146(existingId, existingExtra["messageId"], incomingId, incomingExtra["messageId"])
    "MISSED_CALL" -> isSameMissedCallPayloadOracle146(existingId, existingExtra["callId"], incomingId, incomingExtra["callId"])
    "FRIEND_REQUEST" -> isSameStableExtraOracle146(existingExtra["requestId"], incomingExtra["requestId"])
    "AI_TASK" -> isSameStableExtraOracle146(existingExtra["taskId"], incomingExtra["taskId"])
    else -> false
}

private fun foldIntoOracle146(
    current: List<NotificationCenterItem>,
    item: NotificationCenterItem,
    maxKeep: Int = NotificationCenterFoldPolicy.MAX_KEEP,
): List<NotificationCenterItem> {
    val index = current.indexOfFirst {
        it.type == item.type && it.mergeKey == item.mergeKey && !it.read
    }
    return if (index >= 0) {
        val existing = current[index]
        val skipCount = shouldSkipCountIncrementOracle146(
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
            extra = item.extra,
            id = if (skipCount) existing.id else item.id,
        )
        current.toMutableList().apply { set(index, merged) }
    } else {
        (listOf(item) + current).take(maxKeep)
    }
}

private fun markAllReadOracle146(items: List<NotificationCenterItem>, nowMs: Long): List<NotificationCenterItem> =
    items.map { if (it.read) it else it.copy(read = true, updatedAt = nowMs) }

private fun markReadOracle146(items: List<NotificationCenterItem>, itemId: String, nowMs: Long): List<NotificationCenterItem> =
    items.map { if (it.id == itemId && !it.read) it.copy(read = true, updatedAt = nowMs) else it }

private fun markUnreadOracle146(items: List<NotificationCenterItem>, itemId: String, nowMs: Long): List<NotificationCenterItem> =
    items.map { if (it.id == itemId && it.read) it.copy(read = false, updatedAt = nowMs) else it }

private fun markChatMessagesReadOracle146(items: List<NotificationCenterItem>, chatId: String, nowMs: Long): List<NotificationCenterItem> {
    if (chatId.isBlank()) return items
    return items.map { item ->
        val matches = isChatMessageItemOracle146(chatId, item.mergeKey, item.deeplink, item.extra["chatId"])
        if (matches && !item.read) item.copy(read = true, updatedAt = nowMs) else item
    }
}

private fun markAiTasksReadOracle146(items: List<NotificationCenterItem>, chatId: String, nowMs: Long): List<NotificationCenterItem> {
    if (chatId.isBlank()) return items
    return items.map { item ->
        val matches = item.type == "AI_TASK" &&
            (item.mergeKey == "ai_tasks_$chatId" ||
                item.deeplink == "maodouchat:ai_tasks:$chatId" ||
                item.extra["chatId"] == chatId)
        if (matches && !item.read) item.copy(read = true, updatedAt = nowMs) else item
    }
}

private fun markPostInteractionsReadOracle146(items: List<NotificationCenterItem>, postId: String, nowMs: Long): List<NotificationCenterItem> {
    if (postId.isBlank()) return items
    return items.map { item ->
        val matches = item.type == "POST_INTERACTION" &&
            (item.mergeKey == "post_$postId" || item.extra["postId"] == postId)
        if (matches && !item.read) item.copy(read = true, updatedAt = nowMs) else item
    }
}

private fun removeChatItemsOracle146(items: List<NotificationCenterItem>, chatId: String): List<NotificationCenterItem> {
    if (chatId.isBlank()) return items
    return items.filterNot { item ->
        belongsToChatOracle146(chatId, item.mergeKey, item.deeplink, item.extra["chatId"])
    }
}

private fun removeMessageReferencesOracle146(items: List<NotificationCenterItem>, messageId: String): List<NotificationCenterItem> {
    if (messageId.isBlank()) return items
    return items.filterNot { item ->
        referencesMessageOracle146(messageId, item.id, item.extra["messageId"])
    }
}

private fun deleteItemsForMessagesOracle146(items: List<NotificationCenterItem>, messageIds: List<String>): List<NotificationCenterItem> {
    if (messageIds.isEmpty()) return items
    val ids = messageIds.distinct().filter(String::isNotBlank)
    return items.filterNot { item ->
        ids.any { id -> referencesMessageOracle146(id, item.id, item.extra["messageId"]) }
    }
}

private fun updateMessagePreviewOracle146(items: List<NotificationCenterItem>, messageId: String, preview: String, nowMs: Long): List<NotificationCenterItem> {
    if (messageId.isBlank() || preview.isBlank()) return items
    return items.map { item ->
        val matches = referencesMessageOracle146(messageId, item.id, item.extra["messageId"])
        if (matches && item.preview != preview) item.copy(preview = preview, updatedAt = nowMs) else item
    }
}

private fun item146(
    id: String = "i1",
    type: String = "MESSAGE",
    mergeKey: String = "msg_c1",
    title: String = "t",
    subtitle: String? = null,
    preview: String? = null,
    deeplink: String? = null,
    extra: Map<String, String> = emptyMap(),
    read: Boolean = false,
    count: Int = 1,
    createdAt: Long = 1000L,
    updatedAt: Long = 1000L,
) = NotificationCenterItem(id, type, mergeKey, title, subtitle, preview, deeplink, extra, read, count, createdAt, updatedAt)

// ---------- generators (mirrored by Oracle146 GEN, seed 146003) ----------

private val TYPE_POOL146 = arrayOf(
    "MESSAGE", "MISSED_CALL", "AI_TASK", "POST_INTERACTION", "FRIEND_REQUEST",
    "GROUP_INVITE", "REPORT", "SECURITY", "message", "", "OTHER",
)
private val ID_POOL146 = arrayOf(
    "msg_c1_m_1", "msg_c1", "msg_c1_m_2", "missed_call1", "ai_1", "post_1",
    "item1", "item2", "", "x", "msg_c_a_b_m_1", "msg_c1_m_m1",
)
private val MERGEKEY_POOL146 = arrayOf(
    "msg_c1", "msg_c2", "ai_tasks_c1", "ai_tasks_c2", "post_p1",
    "missed_call1", "", "msg_", "post_", "ai_tasks_",
)
private val TITLE_POOL146 = arrayOf(
    "", "t", "Hello", "  ", "é", "a".repeat(50), "msg_c1",
)
private val DEEPLINK_POOL146 = arrayOf(
    "maodouchat:chat:c1", "maodouchat:ai_tasks:c1", "maodouchat:post:p1",
    "maodouchat:chat:c2", "https://x", "maodouchat:unknown", "",
)
private val EXTRA_KEY_POOL146 = arrayOf(
    "chatId", "messageId", "callId", "taskId", "requestId", "postId", "other",
)
private val EXTRA_VALUE_POOL146 = arrayOf(
    "c1", "c2", "m_1", "m_2", "call1", "task1", "req1", "p1", "", "x",
)
private val COUNT_POOL146 = arrayOf(0, 1, 2, 5, 100)
private val TIME_POOL146 = arrayOf<Long?>(
    0L, 1L, -1L, 1_700_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE,
)
private val NCF_CHATID_POOL146 = arrayOf("c1", "c2", "", " ", "msg_c1", "x")
private val MSGID_POOL146 = arrayOf("m_1", "m_2", "", " ", "x", "m_m1")
private val POSTID_POOL146 = arrayOf("p1", "p2", "", "x")

private fun randomExtra146(rnd: Random): Map<String, String> {
    val n = rnd.nextInt(0, 4)
    val m = linkedMapOf<String, String>()
    repeat(n) {
        m[EXTRA_KEY_POOL146[rnd.nextInt(EXTRA_KEY_POOL146.size)]] =
            EXTRA_VALUE_POOL146[rnd.nextInt(EXTRA_VALUE_POOL146.size)]
    }
    return m
}

private fun randomNullableString146(rnd: Random, pool: Array<String>): String? =
    if (rnd.nextInt(4) == 0) null else pool[rnd.nextInt(pool.size)]

private fun randomItem146(rnd: Random): NotificationCenterItem {
    val title = TITLE_POOL146[rnd.nextInt(TITLE_POOL146.size)]
    return NotificationCenterItem(
        id = ID_POOL146[rnd.nextInt(ID_POOL146.size)],
        type = TYPE_POOL146[rnd.nextInt(TYPE_POOL146.size)],
        mergeKey = MERGEKEY_POOL146[rnd.nextInt(MERGEKEY_POOL146.size)],
        title = title,
        subtitle = randomNullableString146(rnd, TITLE_POOL146),
        preview = randomNullableString146(rnd, TITLE_POOL146),
        deeplink = randomNullableString146(rnd, DEEPLINK_POOL146),
        extra = randomExtra146(rnd),
        read = rnd.nextBoolean(),
        count = COUNT_POOL146[rnd.nextInt(COUNT_POOL146.size)],
        createdAt = TIME_POOL146[rnd.nextInt(TIME_POOL146.size)] ?: 0L,
        updatedAt = TIME_POOL146[rnd.nextInt(TIME_POOL146.size)] ?: 0L,
    )
}

private fun randomItems146(rnd: Random): List<NotificationCenterItem> {
    val n = rnd.nextInt(0, 6)
    return List(n) { randomItem146(rnd) }
}

class NotificationCenterFoldPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val base = item146()
        // foldInto: empty list takes the item.
        assertEquals(
            listOf(base),
            foldIntoOracle146(emptyList(), base),
        )
        assertEquals(
            foldIntoOracle146(emptyList(), base),
            NotificationCenterFoldPolicy.foldInto(emptyList(), base),
        )
        // foldInto: same mergeKey, different messageId -> count + 1, id moves to incoming.
        val cur = listOf(item146(id = "msg_c1_m_1", extra = mapOf("messageId" to "m_1")))
        val inc = item146(id = "msg_c1_m_2", extra = mapOf("messageId" to "m_2"), updatedAt = 2000L, title = "new")
        val merged = foldIntoOracle146(cur, inc)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].count)
        assertEquals("msg_c1_m_2", merged[0].id)
        assertEquals(2000L, merged[0].updatedAt)
        assertEquals("new", merged[0].title)
        assertEquals(foldIntoOracle146(cur, inc), NotificationCenterFoldPolicy.foldInto(cur, inc))
        // foldInto: same messageId -> skip count, keep existing id.
        val incSame = item146(id = "msg_c1_m_9", extra = mapOf("messageId" to "m_1"), updatedAt = 3000L)
        val mergedSame = foldIntoOracle146(cur, incSame)
        assertEquals(1, mergedSame[0].count)
        assertEquals("msg_c1_m_1", mergedSame[0].id)
        assertEquals(3000L, mergedSame[0].updatedAt)
        assertEquals(foldIntoOracle146(cur, incSame), NotificationCenterFoldPolicy.foldInto(cur, incSame))
        // foldInto: missed call, same callId skips, different callId increments.
        val callCur = listOf(item146(id = "missed_call1", type = "MISSED_CALL", mergeKey = "missed_call1", extra = mapOf("callId" to "call1")))
        val callSame = item146(id = "missed_x", type = "MISSED_CALL", mergeKey = "missed_call1", extra = mapOf("callId" to "call1"))
        assertEquals(1, foldIntoOracle146(callCur, callSame)[0].count)
        val callDiff = item146(id = "missed_y", type = "MISSED_CALL", mergeKey = "missed_call1", extra = mapOf("callId" to "call2"))
        assertEquals(2, foldIntoOracle146(callCur, callDiff)[0].count)
        assertEquals(foldIntoOracle146(callCur, callSame), NotificationCenterFoldPolicy.foldInto(callCur, callSame))
        assertEquals(foldIntoOracle146(callCur, callDiff), NotificationCenterFoldPolicy.foldInto(callCur, callDiff))
        // foldInto: friend request / AI task share the stable-extra rule.
        val frCur = listOf(item146(id = "f1", type = "FRIEND_REQUEST", mergeKey = "fr", extra = mapOf("requestId" to "req1")))
        assertEquals(1, foldIntoOracle146(frCur, item146(id = "f2", type = "FRIEND_REQUEST", mergeKey = "fr", extra = mapOf("requestId" to "req1")))[0].count)
        assertEquals(2, foldIntoOracle146(frCur, item146(id = "f2", type = "FRIEND_REQUEST", mergeKey = "fr", extra = mapOf("requestId" to "req2")))[0].count)
        val aiCur = listOf(item146(id = "a1", type = "AI_TASK", mergeKey = "ai_tasks_c1", extra = mapOf("taskId" to "task1")))
        assertEquals(1, foldIntoOracle146(aiCur, item146(id = "a2", type = "AI_TASK", mergeKey = "ai_tasks_c1", extra = mapOf("taskId" to "task1")))[0].count)
        // foldInto: other types always increment; read existing never merges.
        val repCur = listOf(item146(id = "r1", type = "REPORT", mergeKey = "rep", extra = mapOf("messageId" to "m_1")))
        assertEquals(2, foldIntoOracle146(repCur, item146(id = "r2", type = "REPORT", mergeKey = "rep", extra = mapOf("messageId" to "m_1")))[0].count)
        val readCur = listOf(item146(id = "r1", read = true))
        val prepended = foldIntoOracle146(readCur, item146(id = "r2"))
        assertEquals(listOf("r2", "r1"), prepended.map { it.id })
        assertEquals(foldIntoOracle146(readCur, item146(id = "r2")), NotificationCenterFoldPolicy.foldInto(readCur, item146(id = "r2")))
        // foldInto: maxKeep truncates the prepend path (the merge path never truncates).
        assertEquals(emptyList<NotificationCenterItem>(), foldIntoOracle146(emptyList(), base, maxKeep = 0))
        assertEquals(
            listOf(item146(id = "n1", mergeKey = "other")),
            foldIntoOracle146(listOf(item146(id = "o1"), item146(id = "o2")), item146(id = "n1", mergeKey = "other"), maxKeep = 1),
        )
        assertEquals(
            foldIntoOracle146(listOf(item146(id = "o1")), item146(id = "n1", mergeKey = "other"), maxKeep = 1),
            NotificationCenterFoldPolicy.foldInto(listOf(item146(id = "o1")), item146(id = "n1", mergeKey = "other"), maxKeep = 1),
        )
        // markAllRead / markRead / markUnread.
        val mixed = listOf(item146(id = "a", read = false, updatedAt = 1000L), item146(id = "b", read = true, updatedAt = 500L))
        val allRead = markAllReadOracle146(mixed, 9000L)
        assertEquals(true, allRead[0].read)
        assertEquals(9000L, allRead[0].updatedAt)
        assertEquals(500L, allRead[1].updatedAt)
        assertEquals(allRead, NotificationCenterFoldPolicy.markAllRead(mixed, 9000L))
        assertEquals(true, markReadOracle146(mixed, "a", 9000L)[0].read)
        assertEquals(false, markReadOracle146(mixed, "zzz", 9000L)[0].read)
        assertEquals(false, markUnreadOracle146(mixed, "b", 9000L)[1].read)
        assertEquals(markReadOracle146(mixed, "a", 9000L), NotificationCenterFoldPolicy.markRead(mixed, "a", 9000L))
        assertEquals(markUnreadOracle146(mixed, "b", 9000L), NotificationCenterFoldPolicy.markUnread(mixed, "b", 9000L))
        // markChatMessagesRead: msg_ mergeKey / deeplink / extra-with-msg_ only.
        val chatItems = listOf(
            item146(id = "m1", mergeKey = "msg_c1"),
            item146(id = "m2", type = "AI_TASK", mergeKey = "ai_tasks_c1", extra = mapOf("chatId" to "c1")),
            item146(id = "m3", mergeKey = "other", deeplink = "maodouchat:chat:c1"),
            item146(id = "m4", mergeKey = "other", extra = mapOf("chatId" to "c1")),
        )
        val chatRead = markChatMessagesReadOracle146(chatItems, "c1", 9000L)
        assertEquals(listOf(true, false, true, false), chatRead.map { it.read })
        assertEquals(chatRead, NotificationCenterFoldPolicy.markChatMessagesRead(chatItems, "c1", 9000L))
        assertEquals(chatItems, markChatMessagesReadOracle146(chatItems, "", 9000L))
        // markAiTasksRead / markPostInteractionsRead.
        val aiItems = listOf(
            item146(id = "a1", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item146(id = "a2", type = "MESSAGE", mergeKey = "msg_c1", extra = mapOf("chatId" to "c1")),
            item146(id = "a3", type = "AI_TASK", mergeKey = "other", extra = mapOf("chatId" to "c1")),
        )
        val aiRead = markAiTasksReadOracle146(aiItems, "c1", 9000L)
        assertEquals(listOf(true, false, true), aiRead.map { it.read })
        assertEquals(aiRead, NotificationCenterFoldPolicy.markAiTasksRead(aiItems, "c1", 9000L))
        val postItems = listOf(
            item146(id = "p1", type = "POST_INTERACTION", mergeKey = "post_p1"),
            item146(id = "p2", type = "POST_INTERACTION", mergeKey = "other", extra = mapOf("postId" to "p1")),
            item146(id = "p3", type = "MESSAGE", mergeKey = "post_p1"),
        )
        val postRead = markPostInteractionsReadOracle146(postItems, "p1", 9000L)
        assertEquals(listOf(true, true, false), postRead.map { it.read })
        assertEquals(postRead, NotificationCenterFoldPolicy.markPostInteractionsRead(postItems, "p1", 9000L))
        // removeChatItems / removeMessageReferences / deleteItemsForMessages / updateMessagePreview.
        val mixItems = listOf(
            item146(id = "msg_c1_m_1", mergeKey = "msg_c1"),
            item146(id = "ai1", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item146(id = "other1", mergeKey = "other"),
        )
        assertEquals(
            listOf("other1"),
            removeChatItemsOracle146(mixItems, "c1").map { it.id },
        )
        assertEquals(
            removeChatItemsOracle146(mixItems, "c1"),
            NotificationCenterFoldPolicy.removeChatItems(mixItems, "c1"),
        )
        assertEquals(mixItems, removeChatItemsOracle146(mixItems, "", ))
        val refItems = listOf(
            item146(id = "msg_c1_m_1"),
            item146(id = "msg_c_a_b_m_1"),
            item146(id = "msg_c1_m_2"),
            item146(id = "x", extra = mapOf("messageId" to "m_1")),
            item146(id = "y"),
        )
        assertEquals(
            listOf("msg_c1_m_2", "y"),
            removeMessageReferencesOracle146(refItems, "m_1").map { it.id },
        )
        assertEquals(
            removeMessageReferencesOracle146(refItems, "m_1"),
            NotificationCenterFoldPolicy.removeMessageReferences(refItems, "m_1"),
        )
        assertEquals(
            listOf("msg_c1_m_2", "y"),
            deleteItemsForMessagesOracle146(refItems, listOf("m_1", "m_1", "", "  ")).map { it.id },
        )
        assertEquals(
            deleteItemsForMessagesOracle146(refItems, listOf("m_1")),
            NotificationCenterFoldPolicy.deleteItemsForMessages(refItems, listOf("m_1")),
        )
        assertEquals(refItems, deleteItemsForMessagesOracle146(refItems, emptyList()))
        val prevItems = listOf(
            item146(id = "msg_c1_m_1", preview = "old", updatedAt = 1000L),
            item146(id = "msg_c1_m_2", preview = "new", updatedAt = 1000L),
            item146(id = "z", preview = "old", updatedAt = 1000L),
        )
        val updated = updateMessagePreviewOracle146(prevItems, "m_1", "new", 9000L)
        assertEquals("new", updated[0].preview)
        assertEquals(9000L, updated[0].updatedAt)
        assertEquals(1000L, updated[1].updatedAt)
        assertEquals(1000L, updated[2].updatedAt)
        assertEquals(updated, NotificationCenterFoldPolicy.updateMessagePreview(prevItems, "m_1", "new", 9000L))
        assertEquals(prevItems, updateMessagePreviewOracle146(prevItems, "", "new", 9000L))
        assertEquals(prevItems, updateMessagePreviewOracle146(prevItems, "m_1", "  ", 9000L))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(146003)
        repeat(1500) {
            val items = randomItems146(rnd)
            val item = randomItem146(rnd)
            val maxKeep = listOf(0, 1, 2, 3, 520, 521, 1000)[rnd.nextInt(7)]
            val nowMs = TIME_POOL146[rnd.nextInt(TIME_POOL146.size)] ?: 0L
            val chatId = NCF_CHATID_POOL146[rnd.nextInt(NCF_CHATID_POOL146.size)]
            val messageId = MSGID_POOL146[rnd.nextInt(MSGID_POOL146.size)]
            val postId = POSTID_POOL146[rnd.nextInt(POSTID_POOL146.size)]
            val itemId = ID_POOL146[rnd.nextInt(ID_POOL146.size)]
            val preview = TITLE_POOL146[rnd.nextInt(TITLE_POOL146.size)]
            val messageIds = List(rnd.nextInt(0, 4)) { MSGID_POOL146[rnd.nextInt(MSGID_POOL146.size)] }
            assertEquals(foldIntoOracle146(items, item, maxKeep), NotificationCenterFoldPolicy.foldInto(items, item, maxKeep))
            assertEquals(markAllReadOracle146(items, nowMs), NotificationCenterFoldPolicy.markAllRead(items, nowMs))
            assertEquals(markReadOracle146(items, itemId, nowMs), NotificationCenterFoldPolicy.markRead(items, itemId, nowMs))
            assertEquals(markUnreadOracle146(items, itemId, nowMs), NotificationCenterFoldPolicy.markUnread(items, itemId, nowMs))
            assertEquals(markChatMessagesReadOracle146(items, chatId, nowMs), NotificationCenterFoldPolicy.markChatMessagesRead(items, chatId, nowMs))
            assertEquals(markAiTasksReadOracle146(items, chatId, nowMs), NotificationCenterFoldPolicy.markAiTasksRead(items, chatId, nowMs))
            assertEquals(markPostInteractionsReadOracle146(items, postId, nowMs), NotificationCenterFoldPolicy.markPostInteractionsRead(items, postId, nowMs))
            assertEquals(removeChatItemsOracle146(items, chatId), NotificationCenterFoldPolicy.removeChatItems(items, chatId))
            assertEquals(removeMessageReferencesOracle146(items, messageId), NotificationCenterFoldPolicy.removeMessageReferences(items, messageId))
            assertEquals(deleteItemsForMessagesOracle146(items, messageIds), NotificationCenterFoldPolicy.deleteItemsForMessages(items, messageIds))
            assertEquals(updateMessagePreviewOracle146(items, messageId, preview, nowMs), NotificationCenterFoldPolicy.updateMessagePreview(items, messageId, preview, nowMs))
        }
    }

    @Test
    fun sweepExhaustive() {
        val units = (0..0x7F).toList() +
            listOf(0xA0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000) +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        val one = listOf(item146())
        // Single-unit type / mergeKey / id in foldInto.
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            val it = item146(type = ch)
            assertEquals(foldIntoOracle146(one, it), NotificationCenterFoldPolicy.foldInto(one, it))
            val it2 = item146(mergeKey = ch)
            assertEquals(foldIntoOracle146(one, it2), NotificationCenterFoldPolicy.foldInto(one, it2))
            val it3 = item146(id = ch)
            assertEquals(foldIntoOracle146(one, it3), NotificationCenterFoldPolicy.foldInto(one, it3))
        }
        // Single-unit chatId / messageId / postId across the matching ops.
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            assertEquals(markChatMessagesReadOracle146(one, ch, 9000L), NotificationCenterFoldPolicy.markChatMessagesRead(one, ch, 9000L))
            assertEquals(markAiTasksReadOracle146(one, ch, 9000L), NotificationCenterFoldPolicy.markAiTasksRead(one, ch, 9000L))
            assertEquals(removeChatItemsOracle146(one, ch), NotificationCenterFoldPolicy.removeChatItems(one, ch))
            assertEquals(removeMessageReferencesOracle146(one, ch), NotificationCenterFoldPolicy.removeMessageReferences(one, ch))
            assertEquals(markPostInteractionsReadOracle146(one, ch, 9000L), NotificationCenterFoldPolicy.markPostInteractionsRead(one, ch, 9000L))
            assertEquals(updateMessagePreviewOracle146(one, ch, "new", 9000L), NotificationCenterFoldPolicy.updateMessagePreview(one, ch, "new", 9000L))
            assertEquals(updateMessagePreviewOracle146(one, "m_1", ch, 9000L), NotificationCenterFoldPolicy.updateMessagePreview(one, "m_1", ch, 9000L))
        }
        // Count / maxKeep / list-length / nowMs sweeps.
        for (c in 0..5) {
            val it = item146(count = c)
            assertEquals(foldIntoOracle146(one, it), NotificationCenterFoldPolicy.foldInto(one, it))
        }
        for (mk in listOf(0, 1, 2, 3, 520, 521)) {
            assertEquals(foldIntoOracle146(one, item146(id = "n"), mk), NotificationCenterFoldPolicy.foldInto(one, item146(id = "n"), mk))
        }
        for (n in 0..6) {
            val items = List(n) { item146(id = "i$it") }
            assertEquals(markAllReadOracle146(items, 9000L), NotificationCenterFoldPolicy.markAllRead(items, 9000L))
            assertEquals(removeChatItemsOracle146(items, "c1"), NotificationCenterFoldPolicy.removeChatItems(items, "c1"))
        }
        for (now in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals(markAllReadOracle146(one, now), NotificationCenterFoldPolicy.markAllRead(one, now))
        }
    }
}
