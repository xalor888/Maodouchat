package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// The gate is a two-bit decision: only ExternalUrl (kind 0) skips auth;
// every other destination needs a logged-in caller to proceed.
internal fun autRequiresAuth163(kind: Int): Boolean = kind != 0

// Length-prefixed destination echo, so the wire result pins the exact
// destination the decision carries, not just its Proceed/RequireLogin bit.
internal fun autEncodeDest163(kind: Int, first: String, second: String?): String {
    val s2 = second ?: ""
    val flag = if (second == null) -1 else second.length
    return "$kind|${first.length}:$first|$flag:$s2"
}

internal fun autDecisionWire163(kind: Int, first: String, second: String?, loggedIn: Boolean): String {
    val bit = if (autRequiresAuth163(kind) && !loggedIn) "R" else "P"
    return bit + ":" + autEncodeDest163(kind, first, second)
}

// ---------- generators ----------

internal fun autDestination163(kind: Int, first: String, second: String?): AppLinkDestination = when (kind) {
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


internal val AUT_STRING_POOL163 = listOf(
    "", " ", "c1", "alice", "a/b", "a?b", "a b", "é", "中", "\u00a0", "\u0085",
    "\uD83D\uDE00", "a\uD800b", "https://example.com/x", "x".repeat(80),
)

internal val AUT_ALPHABET163 = "ab /?#é中\u00a0\uD83D\uDE00x._-~"

internal fun randomAutString163(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(AUT_ALPHABET163[rnd.nextInt(AUT_ALPHABET163.length)]) }
    return sb.toString()
}

internal data class AutCase163(
    val kind: Int,
    val first: String,
    val second: String?,
    val loggedIn: Boolean,
)

internal fun randomAutCase163(rnd: Random): AutCase163 {
    val kind = rnd.nextInt(11)
    val first = when (rnd.nextInt(10)) {
        0 -> AUT_STRING_POOL163[rnd.nextInt(AUT_STRING_POOL163.size)]
        else -> randomAutString163(rnd)
    }
    val second = when (rnd.nextInt(10)) {
        0 -> null
        1 -> AUT_STRING_POOL163[rnd.nextInt(AUT_STRING_POOL163.size)]
        else -> randomAutString163(rnd)
    }
    return AutCase163(kind, first, second, rnd.nextBoolean())
}

internal fun autJsonEscape163(s: String): String {
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

internal fun autWireJson163(case: AutCase163): String {
    val sb = StringBuilder()
    sb.append("{\"kind\":").append(case.kind)
    sb.append(",\"first\":\"").append(autJsonEscape163(case.first)).append("\"")
    sb.append(",\"second\":")
    if (case.second == null) sb.append("null")
    else sb.append("\"").append(autJsonEscape163(case.second)).append("\"")
    sb.append(",\"loggedIn\":").append(case.loggedIn)
    sb.append("}")
    return sb.toString()
}

class AppLinkAuthGateFuzzTest {

    private fun check(kind: Int, first: String, second: String?, loggedIn: Boolean) {
        val dest = autDestination163(kind, first, second)
        val actual = AppLinkAuthGate.evaluate(dest, loggedIn)
        val actualWire = when (actual) {
            is AppLinkAuthDecision.Proceed -> "P:" + autEncodeDest163(kind, first, second)
            is AppLinkAuthDecision.RequireLogin -> "R:" + autEncodeDest163(kind, first, second)
        }
        assertEquals(autDecisionWire163(kind, first, second, loggedIn), actualWire)
        // The decision must carry the exact destination it was given.
        when (actual) {
            is AppLinkAuthDecision.Proceed -> assertEquals(dest, actual.destination)
            is AppLinkAuthDecision.RequireLogin -> assertEquals(dest, actual.pending)
        }
    }

    private fun check(case: AutCase163) = check(case.kind, case.first, case.second, case.loggedIn)

    @Test
    fun pinnedBoundaries() {
        // ExternalUrl never needs auth, even logged out.
        check(0, "https://example.com/x", null, false)
        check(0, "https://example.com/x", null, true)
        check(0, "", null, false)
        // Every other kind: logged out requires login, logged in proceeds.
        for (kind in 1..10) {
            check(kind, "c1", "m1", false)
            check(kind, "c1", "m1", true)
            check(kind, "", null, false)
            check(kind, "", null, true)
        }
        // Singleton destinations with no id at all.
        for (kind in 6..10) {
            check(kind, "", null, false)
            check(kind, "ignored", "ignored", true)
        }
        for (s in AUT_STRING_POOL163) {
            check(0, s, null, false)
            check(1, s, s, false)
            check(1, s, s, true)
            check(3, s, null, false)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(163002)
        repeat(2000) {
            check(randomAutCase163(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        for (kind in 0..10) {
            for (first in AUT_STRING_POOL163) {
                for (second in listOf<String?>(null, "", "m")) {
                    check(kind, first, second, false)
                    check(kind, first, second, true)
                }
            }
        }
        // Every UTF-16 unit as an id, both login states.
        for (kind in 1..5) {
            for (u in 0..0xFFFF) {
                val c = u.toChar().toString()
                check(kind, c, null, false)
                check(kind, c, null, true)
            }
        }
        // Alphabet pairs as ids.
        for (a in AUT_ALPHABET163) {
            for (b in AUT_ALPHABET163) {
                check(2, a.toString() + b, null, false)
                check(2, a.toString() + b, null, true)
            }
        }
    }
}
