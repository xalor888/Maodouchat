// AppLinkRouter notification/legacy entries (142nd block). Oracle
// reimplements the entries from production text; parseDeepLink /
// scheme parsers were covered by the 29th / 141st blocks.
package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- sanitizer oracles ----------

private fun sanitizeOpaqueIdOracle(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_ID_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> c.isWhitespace() || c.code < 0x20 }) return null
    return single
}

private fun sanitizeUsernameOracle(raw: String): String? {
    val single = raw.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        .take(AppLinkRouter.MAX_USERNAME_LENGTH)
    if (single.isBlank()) return null
    if (single.any { c -> !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') }) return null
    return single
}

private fun sanitizeChatIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun sanitizeMessageIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun sanitizePostIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

private fun parseNotificationExtrasOracle(extras: Map<String, String?>): AppLinkDestination? {
    val chatId = extras["open_chat_id"]?.takeIf { it.isNotBlank() }
    val messageId = extras["open_message_id"]?.takeIf { it.isNotBlank() }
    if (chatId != null) {
        val cleanChat = sanitizeChatIdStrictOracle(chatId) ?: return null
        val cleanMsg = messageId?.let { sanitizeMessageIdStrictOracle(it) ?: return null }
        return AppLinkDestination.ChatDetail(chatId = cleanChat, messageId = cleanMsg)
    }
    extras["open_ai_tasks_chat_id"]?.takeIf { it.isNotBlank() }?.let {
        return AppLinkDestination.AiTasksChat(chatId = sanitizeChatIdStrictOracle(it) ?: return null)
    }
    extras["open_post_id"]?.takeIf { it.isNotBlank() }?.let {
        return AppLinkDestination.PostDetail(postId = sanitizePostIdStrictOracle(it) ?: return null)
    }
    extras["open_username"]?.takeIf { it.isNotBlank() }?.let {
        return AppLinkDestination.PublicProfile(username = sanitizeUsernameOracle(it) ?: return null)
    }
    return null
}

private fun parseLegacyCenterDeeplinkOracle(raw: String): AppLinkDestination? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return when {
        value == "maodouchat:missed_calls" -> AppLinkDestination.MissedCallsTab
        value == "maodouchat:contacts" -> AppLinkDestination.ContactsTab
        value == "maodouchat:group_invites" -> AppLinkDestination.GroupInvitesTab
        value.startsWith("maodouchat:chat:") -> {
            val id = value.removePrefix("maodouchat:chat:").substringBefore(':')
            AppLinkDestination.ChatDetail(chatId = sanitizeChatIdStrictOracle(id) ?: return null)
        }
        value.startsWith("maodouchat:ai_tasks:") -> {
            val id = value.removePrefix("maodouchat:ai_tasks:").substringBefore(':')
            AppLinkDestination.AiTasksChat(chatId = sanitizeChatIdStrictOracle(id) ?: return null)
        }
        value.startsWith("maodouchat:post:") -> {
            val payload = value.removePrefix("maodouchat:post:")
            val postRaw = payload.substringBefore('?').trim()
            val commentRaw = payload.substringAfter("?comment=", "")
                .trim()
                .takeIf { it.isNotBlank() }
            val postId = sanitizePostIdStrictOracle(postRaw) ?: return null
            val commentId = commentRaw?.let { sanitizeMessageIdStrictOracle(it) ?: return null }
            AppLinkDestination.PostDetail(postId = postId, commentId = commentId)
        }
        else -> null
    }
}

// ---------- generators (mirrored by Oracle142 GEN, seed 142001) ----------

private val NT_KEY_POOL = arrayOf(
    "open_chat_id", "open_message_id", "open_ai_tasks_chat_id", "open_post_id",
    "open_username", "other", "", "OPEN_CHAT_ID", "open_chat_id ",
)
private val NT_VALUE_POOL = arrayOf<String?>(
    "c1", "abc-123", "550e8400-e29b-41d4-a716-446655440000", "", "   ",
    "a/b", "a?b", "a#b", "a b", "a!b", "x".repeat(129), "x".repeat(65),
    "é", "u_1", "m_9f2c1a", "123456789", "alice", "a_b", "a-b", "a.b",
    "a\u0000b", "a\u0001b", null,
)
private val NT_SEGMENT_POOL = arrayOf(
    "c1", "m1", "p1", "alice", "a b", "a/b", "a!b", "a_b", "a-b", "a.b",
    "ABC123", "é", "a?b", "a#b", "a%20b", "%41", "a+b", "  padded  ", "0", "-1",
)
private val LEGACY_HEAD_POOL = arrayOf(
    "maodouchat:missed_calls", "maodouchat:contacts", "maodouchat:group_invites",
    "maodouchat:missed_call", "maodouchat:", "maodouchat:chat:",
    "maodouchat:ai_tasks:", "maodouchat:post:", "maodouchat:u/alice",
)

private fun randomText142(rnd: Random, maxLen: Int = 12): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, maxLen + 1)
    repeat(len) {
        when (rnd.nextInt(6)) {
            0 -> sb.append(('a' + rnd.nextInt(26)))
            1 -> sb.append(('0' + rnd.nextInt(10)))
            2 -> sb.append(" \t\n\r\"'\\/{}[],:=#".get(rnd.nextInt(16)))
            3 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            4 -> sb.appendCodePoint(rnd.nextInt(0x10000, 0x1FFFF))
            else -> sb.append(NT_SEGMENT_POOL[rnd.nextInt(NT_SEGMENT_POOL.size)])
        }
    }
    return sb.toString()
}

private fun genNtfExtras(rnd: Random): Map<String, String?> {
    val n = rnd.nextInt(0, 5)
    val map = linkedMapOf<String, String?>()
    repeat(n) {
        val k = if (rnd.nextInt(3) == 0) NT_KEY_POOL[rnd.nextInt(NT_KEY_POOL.size)]
        else randomText142(rnd, 8)
        val v = when (rnd.nextInt(4)) {
            0 -> null
            1 -> NT_VALUE_POOL[rnd.nextInt(NT_VALUE_POOL.size)]
            2 -> randomText142(rnd, 10)
            else -> NT_SEGMENT_POOL[rnd.nextInt(NT_SEGMENT_POOL.size)]
        }
        map[k] = v
    }
    return map
}

private fun randomLegacyId(rnd: Random): String =
    if (rnd.nextInt(3) == 0) NT_SEGMENT_POOL[rnd.nextInt(NT_SEGMENT_POOL.size)]
    else randomText142(rnd, 10)

private fun genLegacy(rnd: Random): String = when (rnd.nextInt(5)) {
    0 -> LEGACY_HEAD_POOL[rnd.nextInt(LEGACY_HEAD_POOL.size)]
    1 -> "maodouchat:chat:" + randomLegacyId(rnd)
    2 -> "maodouchat:ai_tasks:" + randomLegacyId(rnd)
    3 -> "maodouchat:post:" + randomLegacyId(rnd) + "?comment=" + randomLegacyId(rnd)
    else -> randomText142(rnd, 16)
}

class AppLinkNotificationLegacyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val ntfCases = listOf(
            emptyMap(),
            mapOf("open_chat_id" to "c1"),
            mapOf("open_chat_id" to "c1", "open_message_id" to "m1"),
            mapOf("open_chat_id" to "c1", "open_message_id" to "   "),
            mapOf("open_chat_id" to "c1", "open_message_id" to "m1/m2"),
            mapOf("open_chat_id" to "c1/evil"),
            mapOf("open_chat_id" to "   "),
            mapOf("open_chat_id" to "   ", "open_post_id" to "p1"),
            mapOf("open_ai_tasks_chat_id" to "c9"),
            mapOf("open_ai_tasks_chat_id" to "c9/x"),
            mapOf("open_post_id" to "p1"),
            mapOf("open_post_id" to ""),
            mapOf("open_username" to "alice"),
            mapOf("open_username" to "a!b"),
            mapOf("open_username" to "  alice  "),
            mapOf("other" to "x"),
            mapOf<String, String?>("open_chat_id" to null),
            mapOf<String, String?>("open_chat_id" to null, "open_username" to "bob"),
            mapOf("open_chat_id" to "c1", "open_post_id" to "p1"),
            mapOf("open_chat_id" to "x".repeat(129)),
            mapOf<String, String?>("open_chat_id" to "c1", "open_message_id" to null),
            mapOf("open_post_id" to "p1", "open_username" to "alice"),
            mapOf("open_ai_tasks_chat_id" to "c9", "open_post_id" to "p1"),
        )
        for (extras in ntfCases) {
            assertEquals(
                parseNotificationExtrasOracle(extras),
                with(AppLinkRouter) { parseNotificationExtras(extras) },
                "ntf extras=$extras",
            )
        }
        val legacy = listOf(
            "",
            " ",
            "maodouchat:missed_calls",
            "maodouchat:contacts",
            "maodouchat:group_invites",
            "maodouchat:missed_call",
            "maodouchat:missed_calls ",
            " maodouchat:contacts",
            "maodouchat:chat:c1",
            "maodouchat:chat:c1:extra",
            "maodouchat:chat:",
            "maodouchat:chat:c1/evil",
            "maodouchat:chat:c1?x",
            "maodouchat:chat:c1#frag",
            "maodouchat:chat:  c1  ",
            "maodouchat:chat:" + "x".repeat(129),
            "maodouchat:ai_tasks:c9",
            "maodouchat:ai_tasks:",
            "maodouchat:ai_tasks:c9:extra",
            "maodouchat:post:p1",
            "maodouchat:post:p1?comment=cm2",
            "maodouchat:post:p1?comment=",
            "maodouchat:post:p1?comment=cm2?x",
            "maodouchat:post:p1?comment=c1/c2",
            "maodouchat:post:p1?other=x",
            "maodouchat:post:",
            "maodouchat:post:p1?comment=  cm2  ",
            "maodouchat:post:" + "x".repeat(129),
            "maodouchat:chat:c1?comment=x",
            "maodouchat:u/alice",
            "maodouchat://chat/c1",
            "MAODOUCHAT:chat:c1",
            "maodouchat:chat:é",
            "maodouchat:chat:a b",
            "maodouchat:chat:a\u0000b",
            "maodouchat:post:p1?comment=%41",
        )
        for (input in legacy) {
            assertEquals(
                parseLegacyCenterDeeplinkOracle(input),
                with(AppLinkRouter) { parseLegacyCenterDeeplink(input) },
                "legacy input=$input",
            )
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(142001)
        repeat(1500) { i ->
            val extras = genNtfExtras(rnd)
            assertEquals(
                parseNotificationExtrasOracle(extras),
                with(AppLinkRouter) { parseNotificationExtras(extras) },
                "ntf iter=$i extras=$extras",
            )
            val input = genLegacy(rnd)
            assertEquals(
                parseLegacyCenterDeeplinkOracle(input),
                with(AppLinkRouter) { parseLegacyCenterDeeplink(input) },
                "legacy iter=$i input=$input",
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa0\u0000"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        for (base in listOf(
            "maodouchat:chat:c1", "maodouchat:post:p1?comment=cm2",
            "maodouchat:missed_calls", "maodouchat:ai_tasks:c9",
            "maodouchat:contacts", "maodouchat:chat:", "maodouchat:post:",
        )) {
            for (input in editsOf(base)) {
                assertEquals(
                    parseLegacyCenterDeeplinkOracle(input),
                    with(AppLinkRouter) { parseLegacyCenterDeeplink(input) },
                    "legacy sweep input=$input",
                )
            }
        }
        for (code in 0x00..0x7F) {
            val input = String(Character.toChars(code))
            assertEquals(
                parseLegacyCenterDeeplinkOracle(input),
                with(AppLinkRouter) { parseLegacyCenterDeeplink(input) },
                "single-unit legacy code=$code",
            )
        }
        for (base in listOf("c1", "m1", "alice", "p1", "   ", "c1/evil", "")) {
            for (v in editsOf(base)) {
                val extras = mapOf("open_chat_id" to v)
                assertEquals(
                    parseNotificationExtrasOracle(extras),
                    with(AppLinkRouter) { parseNotificationExtras(extras) },
                    "ntf sweep value=$v",
                )
            }
        }
        for (code in 0x00..0x7F) {
            val v = String(Character.toChars(code))
            val extras = mapOf("open_chat_id" to v)
            assertEquals(
                parseNotificationExtrasOracle(extras),
                with(AppLinkRouter) { parseNotificationExtras(extras) },
                "single-unit ntf code=$code",
            )
        }
        for (k in editsOf("open_chat_id")) {
            val extras = mapOf(k to "c1")
            assertEquals(
                parseNotificationExtrasOracle(extras),
                with(AppLinkRouter) { parseNotificationExtras(extras) },
                "ntf sweep key=$k",
            )
        }
    }
}
