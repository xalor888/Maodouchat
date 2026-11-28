// Fuzz / random-oracle tests for resolvedNotificationChatId
// (checklist line 931, 125th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: extra.chatId wins
// verbatim (no trim, only a blank check), then the legacy deeplink parse
// (trim; chat:/ai_tasks: with strict id sanitize; post:/tabs fall through),
// then the msg_/ai_tasks_ mergeKey prefixes with their length guards.
// trim() strips exactly the probed 28-unit Kotlin isWhitespace set
// (probe125); sanitizeOpaqueId cuts at '/' '?' '#' first, then trims,
// then takes 128 units, then rejects blank / interior whitespace / control.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolvedNotificationChatIdFuzzTest {

// ---------- shared oracle ----------

// The probed Kotlin whitespace set (28 UTF-16 code units). Hand-written,
// never calls Char.isWhitespace / String.isBlank() / String.trim().
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean = s.all { it.code in BLANK_UNITS_ORACLE }

private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

private fun sanitizeOpaqueIdOracle(raw: String): String? {
    val single = trimOracle(raw.substringBefore('/').substringBefore('?').substringBefore('#'))
        .let { if (it.length <= 128) it else it.substring(0, 128) }
    if (isBlankOracle(single)) return null
    if (single.any { it.code in BLANK_UNITS_ORACLE || it.code < 0x20 }) return null
    return single
}

private fun sanitizeChatIdStrictOracle(raw: String): String? =
    if (raw.any { it == '/' || it == '?' || it == '#' }) null else sanitizeOpaqueIdOracle(raw)

// Returns the chat id for ChatDetail / AiTasksChat destinations, null for
// tabs, posts, unknown or invalid deeplinks.
private fun parseLegacyOracle(raw: String): String? {
    val value = trimOracle(raw)
    if (value.isEmpty()) return null
    return when {
        value == "maodouchat:missed_calls" -> null
        value == "maodouchat:contacts" -> null
        value == "maodouchat:group_invites" -> null
        value.startsWith("maodouchat:chat:") -> {
            val id = value.removePrefix("maodouchat:chat:").substringBefore(':')
            sanitizeChatIdStrictOracle(id)
        }
        value.startsWith("maodouchat:ai_tasks:") -> {
            val id = value.removePrefix("maodouchat:ai_tasks:").substringBefore(':')
            sanitizeChatIdStrictOracle(id)
        }
        value.startsWith("maodouchat:post:") -> null
        else -> null
    }
}

private fun resolvedOracle(extraChatId: String?, deeplink: String?, mergeKey: String): String? {
    if (extraChatId != null && !isBlankOracle(extraChatId)) return extraChatId
    parseLegacyOracle(deeplink.orEmpty())?.let { return it }
    if (mergeKey.startsWith("msg_") && mergeKey.length > 4) {
        return mergeKey.removePrefix("msg_").takeIf { !isBlankOracle(it) }
    }
    if (mergeKey.startsWith("ai_tasks_") && mergeKey.length > 9) {
        return mergeKey.removePrefix("ai_tasks_").takeIf { !isBlankOracle(it) }
    }
    return null
}

// ---------- GEN ----------

private val EXTRA_POOL = arrayOf(
    "c1", "", " ", "  ", "  c1  ", "c1 ", " c1", "a/b", "a?b", "a#b",
    "x".repeat(200), "\u00A0", "msg_1", "u-123", "üñï", "a:b", "c1\n"
)

private val DEEPLINK_POOL = arrayOf(
    "maodouchat:chat:c1", "maodouchat:chat:c1:extra", "maodouchat:chat:",
    "maodouchat:chat: ", "maodouchat:chat:a/b", "maodouchat:chat:a?b",
    "maodouchat:chat:a#b", "maodouchat:chat:" + "a".repeat(127),
    "maodouchat:chat:" + "a".repeat(128), "maodouchat:chat:" + "a".repeat(129),
    "maodouchat:chat:" + "a".repeat(127) + " b", "  maodouchat:chat:c1  ",
    "maodouchat:ai_tasks:c9", "maodouchat:ai_tasks:", "maodouchat:ai_tasks:a/b",
    "maodouchat:post:p1", "maodouchat:post:p1?comment=c1", "maodouchat:post:p1?comment=",
    "maodouchat:post:", "maodouchat:missed_calls", "maodouchat:contacts",
    "maodouchat:group_invites", "maodouchat:unknown", "maodouchat:chat",
    "http://x", "", " ", "maodouchat:chat:c1?x", "maodouchat:chat:c1/x"
)

private val MERGEKEY_POOL = arrayOf(
    "", "msg_", "msg_c7", "msg_ ", "msg_  ", "msg_a/b", "msg_" + "x".repeat(200),
    "ai_tasks_", "ai_tasks_c8", "ai_tasks_ ", "ai_tasks_" + "y".repeat(200),
    "other", "msg", "ai_tasks", "msg_c7 ", "msg_ c7"
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) {
        val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
        sb.append(Character.toChars(rnd.nextInt(lo, hi + 1)))
    }
    return sb.toString()
}

private fun item(extraChatId: String?, deeplink: String?, mergeKey: String): NotificationCenterItem {
    val extra = if (extraChatId == null) emptyMap() else mapOf("chatId" to extraChatId)
    return NotificationCenterItem(
        id = "n1", type = "MESSAGE", mergeKey = mergeKey, title = "t",
        deeplink = deeplink, extra = extra, createdAt = 0L, updatedAt = 0L
    )
}

// ---------- tests ----------

@Test
fun pinnedBoundaries() {
    // Extra wins verbatim: no trim, no sanitize.
    assertEquals("  c1  ", resolvedNotificationChatId(item("  c1  ", "maodouchat:chat:c2", "msg_c3")))
    assertEquals("a/b", resolvedNotificationChatId(item("a/b", null, "")))
    // Blank extra falls through to the deeplink.
    assertEquals("c1", resolvedNotificationChatId(item("  ", "maodouchat:chat:c1", "")))
    assertEquals("c1", resolvedNotificationChatId(item("\u00A0", "maodouchat:chat:c1", "")))
    // Deeplink: colon suffix cut, surrounding whitespace trimmed.
    assertEquals("c1", resolvedNotificationChatId(item(null, "maodouchat:chat:c1:extra", "")))
    assertEquals("c1", resolvedNotificationChatId(item(null, "  maodouchat:chat:c1  ", "")))
    assertEquals("c9", resolvedNotificationChatId(item(null, "maodouchat:ai_tasks:c9", "")))
    // Invalid chat id in the deeplink falls through to the mergeKey.
    assertEquals("c7", resolvedNotificationChatId(item(null, "maodouchat:chat:a/b", "msg_c7")))
    assertEquals("c7", resolvedNotificationChatId(item(null, "maodouchat:chat:a?b", "msg_c7")))
    assertNull(resolvedNotificationChatId(item(null, "maodouchat:chat:a/b", "")))
    assertNull(resolvedNotificationChatId(item(null, "maodouchat:chat:", "")))
    // 128-unit cut: 129 'a's still resolve (cut to 128), a space at unit
    // 128 is cut away, a space at unit 127 is not.
    assertEquals("a".repeat(128), resolvedNotificationChatId(item(null, "maodouchat:chat:" + "a".repeat(129), "")))
    assertEquals("a".repeat(128), resolvedNotificationChatId(item(null, "maodouchat:chat:" + "a".repeat(128) + " b", "")))
    assertNull(resolvedNotificationChatId(item(null, "maodouchat:chat:" + "a".repeat(127) + " b", "")))
    // Post / tab deeplinks fall through to the mergeKey.
    assertEquals("c7", resolvedNotificationChatId(item(null, "maodouchat:post:p1", "msg_c7")))
    assertEquals("c8", resolvedNotificationChatId(item(null, "maodouchat:missed_calls", "ai_tasks_c8")))
    assertNull(resolvedNotificationChatId(item(null, "maodouchat:post:p1", "")))
    // MergeKey: blank tail resolves to null (msg_ branch returns, no fallthrough).
    assertNull(resolvedNotificationChatId(item(null, null, "msg_ ")))
    assertNull(resolvedNotificationChatId(item(null, null, "msg_")))
    assertEquals("c7", resolvedNotificationChatId(item(null, null, "msg_c7")))
    assertEquals("c8", resolvedNotificationChatId(item(null, null, "ai_tasks_c8")))
    assertNull(resolvedNotificationChatId(item(null, null, "ai_tasks_ ")))
    assertNull(resolvedNotificationChatId(item(null, null, "")))
}

@Test
fun randomOracleMatchesProduction() {
    val rnd = Random(125003)
    repeat(1500) {
        val extra = when (rnd.nextInt(10)) {
            0, 1, 2, 3, 4 -> null
            5 -> randomText(rnd, 20)
            else -> EXTRA_POOL[rnd.nextInt(EXTRA_POOL.size)]
        }
        val deeplink = when (rnd.nextInt(10)) {
            0, 1, 2 -> null
            3 -> randomText(rnd, 40)
            else -> DEEPLINK_POOL[rnd.nextInt(DEEPLINK_POOL.size)]
        }
        val mergeKey = when (rnd.nextInt(4)) {
            0 -> randomText(rnd, 20)
            else -> MERGEKEY_POOL[rnd.nextInt(MERGEKEY_POOL.size)]
        }
        assertEquals(
            resolvedOracle(extra, deeplink, mergeKey),
            resolvedNotificationChatId(item(extra, deeplink, mergeKey))
        )
    }
}

@Test
fun deeplinkAndMergeKeySweep() {
    // Every deeplink x every mergeKey, extra absent.
    for (deeplink in DEEPLINK_POOL) {
        for (mergeKey in MERGEKEY_POOL) {
            assertEquals(
                resolvedOracle(null, deeplink, mergeKey),
                resolvedNotificationChatId(item(null, deeplink, mergeKey))
            )
        }
    }
    // Every extra value against a fixed deeplink + mergeKey.
    for (extra in EXTRA_POOL) {
        assertEquals(
            resolvedOracle(extra, "maodouchat:chat:c2", "msg_c3"),
            resolvedNotificationChatId(item(extra, "maodouchat:chat:c2", "msg_c3"))
        )
    }
}
}
