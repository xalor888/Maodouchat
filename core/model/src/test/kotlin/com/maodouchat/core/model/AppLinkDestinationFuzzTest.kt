package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Path encoder reimplemented from AppLinkEncoding: unreserved ASCII plus
// any Unicode letter/digit kept verbatim, everything else percent-encoded
// per UTF-8 byte; a lone surrogate encodes as '?' (%3F), probed.
internal fun encodePathSegmentOracle163(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw) {
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            sb.append(ch)
        } else {
            for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                sb.append('%')
                sb.append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                sb.append("0123456789ABCDEF"[b.toInt() and 0xF])
            }
        }
    }
    return sb.toString()
}

// Destination kinds: 0 ExternalUrl, 1 ChatDetail, 2 PublicProfile,
// 3 PostDetail, 4 AiTasksChat, 5 GroupInvite, 6 NotificationCenter,
// 7 CallHistory, 8 ContactsTab, 9 MissedCallsTab, 10 GroupInvitesTab.
internal fun routeOracle163(kind: Int, first: String, second: String?): String = when (kind) {
    0 -> error("ExternalUrl is not a Compose route; open via AppLinkOpener")
    1 -> "chat_detail/" + encodePathSegmentOracle163(first) +
        if (second.isNullOrBlank()) "" else "?messageId=" + encodePathSegmentOracle163(second)
    2 -> "public_profile/" + encodePathSegmentOracle163(first)
    3 -> "post/" + encodePathSegmentOracle163(first) + "?comment=" + encodePathSegmentOracle163(second.orEmpty())
    4 -> "ai_tasks/" + encodePathSegmentOracle163(first)
    5 -> "join_group_invite/" + encodePathSegmentOracle163(first)
    6 -> "notification_center"
    7 -> "call_history"
    8 -> "contacts_tab"
    9 -> "missed_calls_tab"
    10 -> "group_invites_tab"
    else -> error("unknown kind $kind")
}

// ---------- generators ----------

internal fun rteDestination163(kind: Int, first: String, second: String?): AppLinkDestination = when (kind) {
    0 -> AppLinkDestination.ExternalUrl(first)
    1 -> AppLinkDestination.ChatDetail(first, second)
    2 -> AppLinkDestination.PublicProfile(first)
    3 -> AppLinkDestination.PostDetail(first, second)
    4 -> AppLinkDestination.AiTasksChat(first)
    5 -> AppLinkDestination.GroupInvite(first)
    6 -> AppLinkDestination.NotificationCenter
    7 -> AppLinkDestination.CallHistory
    8 -> AppLinkDestination.ContactsTab
    9 -> AppLinkDestination.MissedCallsTab
    10 -> AppLinkDestination.GroupInvitesTab
    else -> error("unknown kind $kind")
}


internal val RTE_STRING_POOL163 = listOf(
    "", " ", "  ", "c1", "chat-1", "user_1", "a.b", "a~b", "a/b", "a?b", "a#b", "a b",
    "a+b", "a%b", "a%2F", "%", "%2", "%zz", "é", "中", "群聊", "aé b", "ß", "İ",
    "\u00a0", "\u0085", "\u200b", "a\nb", "a\tb", "x".repeat(64), "x".repeat(65),
    "\uD83D\uDE00", "a\uD83D\uDE00b", "\uD800", "\uDFFF", "a\uD800b",
)

internal val RTE_ALPHABET163 =
    "ab \t\n/%?#+=&é中ßİ\u00a0\u0085\u200b\u2003\u3000\uD83D\uDE00x._-~"

internal fun randomRteString163(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(RTE_ALPHABET163[rnd.nextInt(RTE_ALPHABET163.length)]) }
    return sb.toString()
}

internal data class RteCase163(
    val kind: Int,
    val first: String,
    val second: String?,
)

internal fun randomRteCase163(rnd: Random): RteCase163 {
    val kind = rnd.nextInt(11)
    val first = when (rnd.nextInt(10)) {
        0 -> RTE_STRING_POOL163[rnd.nextInt(RTE_STRING_POOL163.size)]
        else -> randomRteString163(rnd)
    }
    val second = when (rnd.nextInt(10)) {
        0 -> null
        1 -> RTE_STRING_POOL163[rnd.nextInt(RTE_STRING_POOL163.size)]
        else -> randomRteString163(rnd)
    }
    return RteCase163(kind, first, second)
}

internal fun rteJsonEscape163(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun rteWireJson163(case: RteCase163): String {
    val sb = StringBuilder()
    sb.append("{\"kind\":").append(case.kind)
    sb.append(",\"first\":\"").append(rteJsonEscape163(case.first)).append("\"")
    sb.append(",\"second\":")
    if (case.second == null) sb.append("null")
    else sb.append("\"").append(rteJsonEscape163(case.second)).append("\"")
    sb.append("}")
    return sb.toString()
}

class AppLinkDestinationFuzzTest {

    private fun outcome(block: () -> String): String =
        runCatching(block).fold(
            { "OK:$it" },
            { "THROW:${it.javaClass.simpleName}:${it.message}" },
        )

    private fun check(kind: Int, first: String, second: String?) {
        val dest = rteDestination163(kind, first, second)
        assertEquals(
            outcome { routeOracle163(kind, first, second) },
            outcome { dest.toRoute() },
        )
    }

    private fun check(case: RteCase163) = check(case.kind, case.first, case.second)

    @Test
    fun pinnedBoundaries() {
        assertEquals("chat_detail/c1", AppLinkDestination.ChatDetail("c1").toRoute())
        assertEquals("chat_detail/c1?messageId=m9", AppLinkDestination.ChatDetail("c1", "m9").toRoute())
        assertEquals("chat_detail/c1", AppLinkDestination.ChatDetail("c1", " ").toRoute())
        assertEquals("chat_detail/c1", AppLinkDestination.ChatDetail("c1", "").toRoute())
        assertEquals("chat_detail/c%201?messageId=m%2F2", AppLinkDestination.ChatDetail("c 1", "m/2").toRoute())
        assertEquals("chat_detail/", AppLinkDestination.ChatDetail("").toRoute())
        assertEquals("public_profile/alice", AppLinkDestination.PublicProfile("alice").toRoute())
        assertEquals("public_profile/a%20b", AppLinkDestination.PublicProfile("a b").toRoute())
        assertEquals("post/p?comment=c", AppLinkDestination.PostDetail("p", "c").toRoute())
        assertEquals("post/p?comment=", AppLinkDestination.PostDetail("p").toRoute())
        assertEquals("post/p?comment=%20", AppLinkDestination.PostDetail("p", " ").toRoute())
        assertEquals("ai_tasks/c1", AppLinkDestination.AiTasksChat("c1").toRoute())
        assertEquals("join_group_invite/Ab-9_", AppLinkDestination.GroupInvite("Ab-9_").toRoute())
        assertEquals("notification_center", AppLinkDestination.NotificationCenter.toRoute())
        assertEquals("call_history", AppLinkDestination.CallHistory.toRoute())
        assertEquals("contacts_tab", AppLinkDestination.ContactsTab.toRoute())
        assertEquals("missed_calls_tab", AppLinkDestination.MissedCallsTab.toRoute())
        assertEquals("group_invites_tab", AppLinkDestination.GroupInvitesTab.toRoute())
        // ExternalUrl has no Compose route: both sides throw the same error.
        check(0, "https://example.com/x", null)
        check(0, "", null)
        // Blank messageId is dropped, but a blank comment still encodes.
        check(1, "c1", "  ")
        check(1, "c1", "\u00a0")
        check(3, "p", "  ")
        check(3, "p", null)
        // Percent and surrogate halves in ids.
        check(1, "a%2Fb", "m%zz")
        check(2, "\uD83D\uDE00", null)
        check(2, "a\uD800b", null)
        check(5, "ß", null)
        check(5, "İ", null)
        for (kind in 0..10) {
            check(kind, "c1", "m1")
            check(kind, "", null)
            check(kind, " ", " ")
        }
        for (s in RTE_STRING_POOL163) {
            check(1, s, "m")
            check(2, s, null)
            check(3, s, s)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(163001)
        repeat(2000) {
            check(randomRteCase163(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        for (kind in 0..10) {
            for (first in RTE_STRING_POOL163) {
                for (second in listOf<String?>(null, "", " ", "m", "a/b")) {
                    check(kind, first, second)
                }
            }
        }
        // Every UTF-16 unit as the id field of each id-carrying kind.
        for (kind in 1..5) {
            for (u in 0..0xFFFF) {
                val c = u.toChar().toString()
                check(kind, c, null)
            }
        }
        // Every UTF-16 unit as the optional second field.
        for (kind in listOf(1, 3)) {
            for (u in 0..0xFFFF) {
                val c = u.toChar().toString()
                check(kind, "c1", c)
            }
        }
        // Alphabet pairs as usernames.
        for (a in RTE_ALPHABET163) {
            for (b in RTE_ALPHABET163) {
                check(2, a.toString() + b, null)
            }
        }
    }
}
