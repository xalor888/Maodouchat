// NotificationTrayDismissPolicy fuzz (147th block). Oracle reimplements
// the three routing functions from production text, including the two
// already-fuzz-covered delegates it calls: parseLegacyCenterDeeplink
// (fuzz-142) and NotificationCenterReadPolicy.belongsToChat (fuzz-106).
// This block covers the DismissPolicy functions themselves, not the
// delegates. Items are plain data; createdAt/updatedAt are always
// passed explicitly, never System.currentTimeMillis().
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.AppLinkDestination
import com.maodouchat.core.model.NotificationCenterItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private fun sanitizeOpaqueIdOracle147(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim().take(128)
    if (single.isBlank()) return null
    if (single.any { it.isWhitespace() || it.code < 0x20 }) return null
    return single
}

private fun sanitizeChatIdStrictOracle147(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle147(raw)

private fun sanitizePostIdStrictOracle147(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle147(raw)

private fun sanitizeMessageIdStrictOracle147(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle147(raw)

private fun parseLegacyCenterDeeplinkOracle147(raw: String): AppLinkDestination? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return when {
        value == "maodouchat:missed_calls" -> AppLinkDestination.MissedCallsTab
        value == "maodouchat:contacts" -> AppLinkDestination.ContactsTab
        value == "maodouchat:group_invites" -> AppLinkDestination.GroupInvitesTab
        value.startsWith("maodouchat:chat:") -> {
            val id = value.removePrefix("maodouchat:chat:").substringBefore(':')
            AppLinkDestination.ChatDetail(chatId = sanitizeChatIdStrictOracle147(id) ?: return null)
        }
        value.startsWith("maodouchat:ai_tasks:") -> {
            val id = value.removePrefix("maodouchat:ai_tasks:").substringBefore(':')
            AppLinkDestination.AiTasksChat(chatId = sanitizeChatIdStrictOracle147(id) ?: return null)
        }
        value.startsWith("maodouchat:post:") -> {
            val payload = value.removePrefix("maodouchat:post:")
            val postRaw = payload.substringBefore('?').trim()
            val commentRaw = payload.substringAfter("?comment=", "").trim().takeIf { it.isNotBlank() }
            val postId = sanitizePostIdStrictOracle147(postRaw) ?: return null
            val commentId = commentRaw?.let { sanitizeMessageIdStrictOracle147(it) ?: return null }
            AppLinkDestination.PostDetail(postId = postId, commentId = commentId)
        }
        else -> null
    }
}

private fun isChatMessageItemOracle147(chatId: String, mergeKey: String, deeplink: String?, extraChatId: String?): Boolean {
    if (chatId.isBlank()) return false
    if (mergeKey == "msg_$chatId") return true
    if (deeplink == "maodouchat:chat:$chatId") return true
    if (extraChatId == chatId && mergeKey.startsWith("msg_")) return true
    return false
}

private fun belongsToChatOracle147(chatId: String, mergeKey: String, deeplink: String?, extraChatId: String?): Boolean {
    if (chatId.isBlank()) return false
    if (isChatMessageItemOracle147(chatId, mergeKey, deeplink, extraChatId)) return true
    if (mergeKey == "ai_tasks_$chatId") return true
    if (deeplink == "maodouchat:ai_tasks:$chatId") return true
    if (extraChatId == chatId) return true
    return false
}

private fun resolveLiveItemOracle147(
    items: List<NotificationCenterItem>,
    id: String,
    hint: NotificationCenterItem? = null,
): NotificationCenterItem? {
    items.firstOrNull { it.id == id }?.let { return it }
    val type = hint?.type
    val mergeKey = hint?.mergeKey
    if (!type.isNullOrBlank() && !mergeKey.isNullOrBlank()) {
        items.firstOrNull { it.type == type && it.mergeKey == mergeKey }?.let { return it }
    }
    return null
}

private fun dismissActionForOracle147(item: NotificationCenterItem): NotificationTrayDismissAction? {
    when {
        item.type == "MISSED_CALL" -> {
            val callId = item.extra["callId"].orEmpty()
            if (callId.isNotBlank()) return NotificationTrayDismissAction.CancelMissedCall(callId)
        }
        item.type == "AI_TASK" -> {
            val taskId = item.extra["taskId"].orEmpty()
            val chatId = item.extra["chatId"].orEmpty()
            when {
                chatId.isNotBlank() -> return NotificationTrayDismissAction.CancelAiTaskRemindersForChat(chatId)
                taskId.isNotBlank() -> return NotificationTrayDismissAction.CancelAiTaskReminder(taskId)
            }
        }
        item.type == "MESSAGE" || item.deeplink?.startsWith("maodouchat:chat:") == true -> {
            val chatId = item.extra["chatId"]
                ?: (parseLegacyCenterDeeplinkOracle147(item.deeplink.orEmpty())
                    as? AppLinkDestination.ChatDetail)?.chatId
                ?: item.mergeKey.removePrefix("msg_")
            if (chatId.isNotBlank()) return NotificationTrayDismissAction.CancelMessage(chatId)
        }
        item.type == "POST_INTERACTION" ||
            item.deeplink?.startsWith("maodouchat:post:") == true -> {
            val postId = item.extra["postId"]
                ?: (parseLegacyCenterDeeplinkOracle147(item.deeplink.orEmpty())
                    as? AppLinkDestination.PostDetail)?.postId
                ?: item.mergeKey.removePrefix("post_")
            if (postId.isNotBlank()) return NotificationTrayDismissAction.CancelPostInteraction(postId)
        }
        item.type == "FRIEND_REQUEST" ||
            parseLegacyCenterDeeplinkOracle147(item.deeplink.orEmpty())
                is AppLinkDestination.ContactsTab -> {
            return NotificationTrayDismissAction.CancelAllFriendRequests
        }
        item.type == "GROUP_INVITE" ||
            parseLegacyCenterDeeplinkOracle147(item.deeplink.orEmpty())
                is AppLinkDestination.GroupInvitesTab -> {
            return NotificationTrayDismissAction.CancelAllGroupInvites
        }
    }
    return null
}

private fun affectedItemsForChatOracle147(items: List<NotificationCenterItem>, chatId: String): List<NotificationCenterItem> =
    items.filter { item ->
        belongsToChatOracle147(
            chatId = chatId,
            mergeKey = item.mergeKey,
            deeplink = item.deeplink,
            extraChatId = item.extra["chatId"]
        )
    }

private fun item147(
    id: String = "n1",
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

// ---------- generators (mirrored by Oracle147 GEN, seed 147001) ----------

private val TYPE_POOL147 = arrayOf(
    "MESSAGE", "MISSED_CALL", "AI_TASK", "POST_INTERACTION", "FRIEND_REQUEST",
    "GROUP_INVITE", "REPORT", "SECURITY", "message", "", "OTHER",
)
private val ID_POOL147 = arrayOf(
    "msg_c1_m_1", "msg_c1", "msg_c1_m_2", "missed_call1", "ai_1", "post_1",
    "item1", "item2", "", "x", "msg_c_a_b_m_1", "msg_c1_m_m1",
)
private val MERGEKEY_POOL147 = arrayOf(
    "msg_c1", "msg_c2", "ai_tasks_c1", "ai_tasks_c2", "post_p1",
    "missed_call1", "", "msg_", "post_", "ai_tasks_", "friend_request", "group_invite",
)
private val TITLE_POOL147 = arrayOf(
    "", "t", "Hello", "  ", "é", "a".repeat(50), "msg_c1",
)
private val DEEPLINK_POOL147 = arrayOf(
    "maodouchat:chat:c1", "maodouchat:chat:c2", "maodouchat:chat:c1/evil",
    "maodouchat:chat:c1?x=1", "maodouchat:chat:", "maodouchat:chat:c1:extra",
    "maodouchat:ai_tasks:c1", "maodouchat:post:p1", "maodouchat:post:p1?comment=cm2",
    "maodouchat:post:p1/p2", "maodouchat:post:", "maodouchat:contacts",
    "maodouchat:group_invites", "maodouchat:missed_calls", "maodouchat:unknown",
    "https://x", "", " maodouchat:chat:c1 ", "maodouchat:chat: c1",
)
private val EXTRA_KEY_POOL147 = arrayOf(
    "chatId", "messageId", "callId", "taskId", "requestId", "postId", "other",
)
private val EXTRA_VALUE_POOL147 = arrayOf(
    "c1", "c2", "m_1", "m_2", "call1", "task1", "req1", "p1", "", " ", "x", "c1/evil",
)
private val COUNT_POOL147 = arrayOf(0, 1, 2, 5, 100)
private val TIME_POOL147 = arrayOf<Long?>(
    0L, 1L, -1L, 1_700_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE,
)
private val NTD_CHATID_POOL147 = arrayOf("c1", "c2", "", " ", "msg_c1", "x")

private fun randomExtra147(rnd: Random): Map<String, String> {
    val n = rnd.nextInt(0, 4)
    val m = linkedMapOf<String, String>()
    repeat(n) {
        m[EXTRA_KEY_POOL147[rnd.nextInt(EXTRA_KEY_POOL147.size)]] =
            EXTRA_VALUE_POOL147[rnd.nextInt(EXTRA_VALUE_POOL147.size)]
    }
    return m
}

private fun randomNullableString147(rnd: Random, pool: Array<String>): String? =
    if (rnd.nextInt(4) == 0) null else pool[rnd.nextInt(pool.size)]

private fun randomItem147(rnd: Random): NotificationCenterItem {
    val title = TITLE_POOL147[rnd.nextInt(TITLE_POOL147.size)]
    return NotificationCenterItem(
        id = ID_POOL147[rnd.nextInt(ID_POOL147.size)],
        type = TYPE_POOL147[rnd.nextInt(TYPE_POOL147.size)],
        mergeKey = MERGEKEY_POOL147[rnd.nextInt(MERGEKEY_POOL147.size)],
        title = title,
        subtitle = randomNullableString147(rnd, TITLE_POOL147),
        preview = randomNullableString147(rnd, TITLE_POOL147),
        deeplink = randomNullableString147(rnd, DEEPLINK_POOL147),
        extra = randomExtra147(rnd),
        read = rnd.nextBoolean(),
        count = COUNT_POOL147[rnd.nextInt(COUNT_POOL147.size)],
        createdAt = TIME_POOL147[rnd.nextInt(TIME_POOL147.size)] ?: 0L,
        updatedAt = TIME_POOL147[rnd.nextInt(TIME_POOL147.size)] ?: 0L,
    )
}

private fun randomItems147(rnd: Random): List<NotificationCenterItem> {
    val n = rnd.nextInt(0, 6)
    return List(n) { randomItem147(rnd) }
}

class NotificationTrayDismissPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // resolveLiveItem: id wins over hint; hint fallback needs both parts non-blank.
        val items = listOf(item147(id = "old"), item147(id = "n1", mergeKey = "other"))
        assertEquals("n1", resolveLiveItemOracle147(items, "n1")?.id)
        assertEquals(resolveLiveItemOracle147(items, "n1"), NotificationTrayDismissPolicy.resolveLiveItem(items, "n1"))
        val hintItems = listOf(item147(id = "head", type = "MESSAGE", mergeKey = "msg_c1"))
        val hint = item147(id = "stale", type = "MESSAGE", mergeKey = "msg_c1")
        assertEquals("head", resolveLiveItemOracle147(hintItems, "stale", hint)?.id)
        assertEquals(resolveLiveItemOracle147(hintItems, "stale", hint), NotificationTrayDismissPolicy.resolveLiveItem(hintItems, "stale", hint))
        assertEquals(null, resolveLiveItemOracle147(hintItems, "nope", item147(id = "x", type = "MESSAGE", mergeKey = "msg_c9")))
        assertEquals(null, resolveLiveItemOracle147(hintItems, "nope", item147(id = "x", type = "MESSAGE", mergeKey = "")))
        assertEquals(null, resolveLiveItemOracle147(hintItems, "nope", item147(id = "x", type = "", mergeKey = "msg_c1")))
        assertEquals(null, resolveLiveItemOracle147(hintItems, "nope", null))
        assertEquals(null, resolveLiveItemOracle147(emptyList(), "n1", hint))
        // dismissActionFor: missed call needs a non-blank callId.
        assertEquals(
            NotificationTrayDismissAction.CancelMissedCall("call-1"),
            dismissActionForOracle147(item147(type = "MISSED_CALL", mergeKey = "missed_call-1", extra = mapOf("callId" to "call-1"))),
        )
        assertEquals(
            dismissActionForOracle147(item147(type = "MISSED_CALL", mergeKey = "missed_call-1", extra = mapOf("callId" to "call-1"))),
            NotificationTrayDismissPolicy.dismissActionFor(item147(type = "MISSED_CALL", mergeKey = "missed_call-1", extra = mapOf("callId" to "call-1"))),
        )
        assertEquals(null, dismissActionForOracle147(item147(type = "MISSED_CALL", mergeKey = "missed_call-1")))
        assertEquals(null, dismissActionForOracle147(item147(type = "MISSED_CALL", mergeKey = "missed_call-1", extra = mapOf("callId" to "  "))))
        // dismissActionFor: AI task prefers chat over task; blank extra blocks the fallback.
        assertEquals(
            NotificationTrayDismissAction.CancelAiTaskRemindersForChat("c1"),
            dismissActionForOracle147(item147(type = "AI_TASK", mergeKey = "ai_tasks_c1", extra = mapOf("chatId" to "c1", "taskId" to "t1"))),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAiTaskReminder("t1"),
            dismissActionForOracle147(item147(type = "AI_TASK", mergeKey = "ai_task_t1", extra = mapOf("taskId" to "t1"))),
        )
        assertEquals(null, dismissActionForOracle147(item147(type = "AI_TASK", mergeKey = "ai_task_x")))
        assertEquals(null, dismissActionForOracle147(item147(type = "AI_TASK", mergeKey = "ai_task_x", extra = mapOf("chatId" to " "))))
        // dismissActionFor: message resolves chatId from extra, then deeplink, then mergeKey.
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c1"),
            dismissActionForOracle147(item147(extra = mapOf("chatId" to "c1"))),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c9"),
            dismissActionForOracle147(item147(deeplink = "maodouchat:chat:c9", mergeKey = "msg_c9")),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c9"),
            dismissActionForOracle147(item147(mergeKey = "msg_c9")),
        )
        assertEquals(null, dismissActionForOracle147(item147(mergeKey = "msg_")))
        // Blank extra chatId blocks the deeplink/mergeKey fallback.
        assertEquals(null, dismissActionForOracle147(item147(deeplink = "maodouchat:chat:c9", mergeKey = "msg_c9", extra = mapOf("chatId" to ""))))
        // Deeplink with an illegal chatId falls through to the mergeKey.
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c9"),
            dismissActionForOracle147(item147(deeplink = "maodouchat:chat:c1/evil", mergeKey = "msg_c9")),
        )
        // A chat deeplink on a non-MESSAGE type still takes the message branch.
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("c9"),
            dismissActionForOracle147(item147(type = "REPORT", mergeKey = "msg_c9", deeplink = "maodouchat:chat:c9")),
        )
        // A post deeplink on a MESSAGE row does not take the post branch.
        assertEquals(
            NotificationTrayDismissAction.CancelMessage("p1"),
            dismissActionForOracle147(item147(deeplink = "maodouchat:post:p1", mergeKey = "msg_p1")),
        )
        assertEquals(
            dismissActionForOracle147(item147(deeplink = "maodouchat:chat:c9", mergeKey = "msg_c9")),
            NotificationTrayDismissPolicy.dismissActionFor(item147(deeplink = "maodouchat:chat:c9", mergeKey = "msg_c9")),
        )
        // dismissActionFor: post resolves postId from extra, then deeplink, then mergeKey.
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p1"),
            dismissActionForOracle147(item147(type = "POST_INTERACTION", mergeKey = "post_p1", extra = mapOf("postId" to "p1"))),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p2"),
            dismissActionForOracle147(item147(type = "POST_INTERACTION", mergeKey = "post_p2", deeplink = "maodouchat:post:p2")),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p3"),
            dismissActionForOracle147(item147(type = "POST_INTERACTION", mergeKey = "post_p3")),
        )
        assertEquals(null, dismissActionForOracle147(item147(type = "POST_INTERACTION", mergeKey = "post_")))
        assertEquals(null, dismissActionForOracle147(item147(type = "POST_INTERACTION", mergeKey = "post_p3", extra = mapOf("postId" to " "))))
        assertEquals(
            NotificationTrayDismissAction.CancelPostInteraction("p3"),
            dismissActionForOracle147(item147(type = "REPORT", mergeKey = "post_p3", deeplink = "maodouchat:post:p1/p2")),
        )
        assertEquals(
            dismissActionForOracle147(item147(type = "POST_INTERACTION", mergeKey = "post_p2", deeplink = "maodouchat:post:p2")),
            NotificationTrayDismissPolicy.dismissActionFor(item147(type = "POST_INTERACTION", mergeKey = "post_p2", deeplink = "maodouchat:post:p2")),
        )
        // dismissActionFor: friend request / group invite via type or deeplink.
        assertEquals(
            NotificationTrayDismissAction.CancelAllFriendRequests,
            dismissActionForOracle147(item147(type = "FRIEND_REQUEST", mergeKey = "friend_request")),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAllFriendRequests,
            dismissActionForOracle147(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:contacts")),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAllGroupInvites,
            dismissActionForOracle147(item147(type = "GROUP_INVITE", mergeKey = "group_invite")),
        )
        assertEquals(
            NotificationTrayDismissAction.CancelAllGroupInvites,
            dismissActionForOracle147(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:group_invites")),
        )
        assertEquals(
            dismissActionForOracle147(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:contacts")),
            NotificationTrayDismissPolicy.dismissActionFor(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:contacts")),
        )
        assertEquals(
            dismissActionForOracle147(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:group_invites")),
            NotificationTrayDismissPolicy.dismissActionFor(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:group_invites")),
        )
        // dismissActionFor: unknown type returns null; missed-calls deeplink alone is not a dismiss.
        assertEquals(null, dismissActionForOracle147(item147(type = "SECURITY", mergeKey = "sec_1")))
        assertEquals(null, dismissActionForOracle147(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:missed_calls")))
        assertEquals(null, dismissActionForOracle147(item147(type = "REPORT", mergeKey = "report_1", deeplink = "maodouchat:ai_tasks:c1")))
        // affectedItemsForChat: message + ai-task rows of the chat, nothing else.
        val affItems = listOf(
            item147(id = "a", mergeKey = "msg_c1"),
            item147(id = "b", type = "AI_TASK", mergeKey = "ai_tasks_c1"),
            item147(id = "c", mergeKey = "msg_c2"),
            item147(id = "d", type = "REPORT", mergeKey = "report_1"),
            item147(id = "e", mergeKey = "other", extra = mapOf("chatId" to "c1")),
        )
        assertEquals(listOf("a", "b", "e"), affectedItemsForChatOracle147(affItems, "c1").map { it.id })
        assertEquals(
            affectedItemsForChatOracle147(affItems, "c1"),
            NotificationTrayDismissPolicy.affectedItemsForChat(affItems, "c1"),
        )
        assertEquals(emptyList(), affectedItemsForChatOracle147(affItems, ""))
        assertEquals(emptyList(), affectedItemsForChatOracle147(emptyList(), "c1"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(147001)
        repeat(1500) {
            val items = randomItems147(rnd)
            val item = randomItem147(rnd)
            val id = ID_POOL147[rnd.nextInt(ID_POOL147.size)]
            val hint = if (rnd.nextInt(4) == 0) null else randomItem147(rnd)
            val chatId = NTD_CHATID_POOL147[rnd.nextInt(NTD_CHATID_POOL147.size)]
            assertEquals(resolveLiveItemOracle147(items, id, hint), NotificationTrayDismissPolicy.resolveLiveItem(items, id, hint))
            assertEquals(dismissActionForOracle147(item), NotificationTrayDismissPolicy.dismissActionFor(item))
            assertEquals(affectedItemsForChatOracle147(items, chatId), NotificationTrayDismissPolicy.affectedItemsForChat(items, chatId))
        }
    }

    @Test
    fun sweepExhaustive() {
        val units = (0..0x7F).toList() +
            listOf(0xA0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000) +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        val one = listOf(item147())
        // Single-unit type / mergeKey / id / deeplink in dismissActionFor.
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            val byType = item147(type = ch)
            assertEquals(dismissActionForOracle147(byType), NotificationTrayDismissPolicy.dismissActionFor(byType))
            val byMerge = item147(mergeKey = ch)
            assertEquals(dismissActionForOracle147(byMerge), NotificationTrayDismissPolicy.dismissActionFor(byMerge))
            val byId = item147(id = ch)
            assertEquals(resolveLiveItemOracle147(one, ch), NotificationTrayDismissPolicy.resolveLiveItem(one, ch))
            assertEquals(dismissActionForOracle147(byId), NotificationTrayDismissPolicy.dismissActionFor(byId))
            val byDeep = item147(deeplink = ch)
            assertEquals(dismissActionForOracle147(byDeep), NotificationTrayDismissPolicy.dismissActionFor(byDeep))
            val byDeepChat = item147(deeplink = "maodouchat:chat:$ch")
            assertEquals(dismissActionForOracle147(byDeepChat), NotificationTrayDismissPolicy.dismissActionFor(byDeepChat))
            val byDeepPost = item147(type = "POST_INTERACTION", mergeKey = "post_", deeplink = "maodouchat:post:$ch")
            assertEquals(dismissActionForOracle147(byDeepPost), NotificationTrayDismissPolicy.dismissActionFor(byDeepPost))
            assertEquals(affectedItemsForChatOracle147(one, ch), NotificationTrayDismissPolicy.affectedItemsForChat(one, ch))
        }
        // Deeplink prefix / suffix variations around the legacy schemes.
        for (prefix in listOf("maodouchat:chat:", "maodouchat:post:", "maodouchat:ai_tasks:", "maodouchat:")) {
            for (suffix in listOf("", "c1", "c1/evil", "c1?x=1", "c1:extra", " ", "c1#c", "/c1")) {
                val it = item147(deeplink = prefix + suffix)
                assertEquals(dismissActionForOracle147(it), NotificationTrayDismissPolicy.dismissActionFor(it))
                val itPost = item147(type = "POST_INTERACTION", mergeKey = "post_", deeplink = prefix + suffix)
                assertEquals(dismissActionForOracle147(itPost), NotificationTrayDismissPolicy.dismissActionFor(itPost))
            }
        }
        // Extra key/value variations on the load-bearing keys.
        for (key in EXTRA_KEY_POOL147) {
            for (value in EXTRA_VALUE_POOL147) {
                val it = item147(extra = mapOf(key to value))
                assertEquals(dismissActionForOracle147(it), NotificationTrayDismissPolicy.dismissActionFor(it))
                val itCall = item147(type = "MISSED_CALL", mergeKey = "missed_call1", extra = mapOf(key to value))
                assertEquals(dismissActionForOracle147(itCall), NotificationTrayDismissPolicy.dismissActionFor(itCall))
                val itTask = item147(type = "AI_TASK", mergeKey = "ai_task_t1", extra = mapOf(key to value))
                assertEquals(dismissActionForOracle147(itTask), NotificationTrayDismissPolicy.dismissActionFor(itTask))
            }
        }
        // List-length sweep for resolve / affected.
        for (n in 0..6) {
            val items = List(n) { item147(id = "i$it") }
            assertEquals(resolveLiveItemOracle147(items, "i1"), NotificationTrayDismissPolicy.resolveLiveItem(items, "i1"))
            assertEquals(affectedItemsForChatOracle147(items, "c1"), NotificationTrayDismissPolicy.affectedItemsForChat(items, "c1"))
        }
    }
}
