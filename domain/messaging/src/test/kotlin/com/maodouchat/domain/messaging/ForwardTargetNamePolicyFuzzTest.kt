package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// displayName chain, reimplemented from User.kt: nickname wins when
// non-blank, then name, then id as-is.
internal fun displayNameOracle162(nickname: String?, name: String, id: String): String =
    nickname?.takeIf(String::isNotBlank) ?: name.takeIf(String::isNotBlank) ?: id

internal fun resolveOracle162(
    isGroup: Boolean,
    groupName: String?,
    participants: List<Triple<String, String, String?>>,
    currentUserId: String,
): String {
    if (isGroup) {
        if (groupName != null) return "GN:$groupName"
        return "GC:${participants.size}"
    }
    val peer = participants.firstOrNull { it.first != currentUserId }
    if (peer != null) return "P:" + displayNameOracle162(peer.third, peer.second, peer.first)
    return "PF"
}

// ---------- generators ----------

internal val FWD_ID_POOL162 = listOf("me", "u1", "u2", "", "peer")

internal val FWD_NAME_POOL162 = listOf(
    "", " ", "  ", "Alice", "Bob", "  Al  ", "\u00a0", "\u0085", "\u200b", "中",
    "\uD83D\uDE00", "a",
)

internal val FWD_NICK_POOL162: List<String?> = listOf(
    null, "", " ", "  ", "Nick", "\u00a0", "\u0085", "中",
)

internal val FWD_GROUP_POOL162: List<String?> = listOf(
    null, "", " ", "Dev Team", "  G  ", "\u00a0", "群",
)

internal val FWD_ALPHABET162 =
    "ab \t\n\u00a0\u0085\u200b\u2003\u3000\u00e9\u4e2d\uD83D\uDE00x"

internal fun randomFwdName162(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(FWD_ALPHABET162[rnd.nextInt(FWD_ALPHABET162.length)]) }
    return sb.toString()
}

internal data class FwdCase162(
    val isGroup: Boolean,
    val groupName: String?,
    val participants: List<Triple<String, String, String?>>,
    val currentUserId: String,
)

internal fun randomFwdCase162(rnd: Random): FwdCase162 {
    val isGroup = rnd.nextBoolean()
    val groupName = when (rnd.nextInt(10)) {
        0 -> null
        1 -> FWD_GROUP_POOL162[rnd.nextInt(FWD_GROUP_POOL162.size)]
        else -> randomFwdName162(rnd)
    }
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> 1
        2 -> rnd.nextInt(2, 6)
        else -> rnd.nextInt(0, 4)
    }
    val participants = List(count) {
        val id = when (rnd.nextInt(10)) {
            0 -> "me"
            1 -> FWD_ID_POOL162[rnd.nextInt(FWD_ID_POOL162.size)]
            else -> randomFwdName162(rnd)
        }
        val name = when (rnd.nextInt(10)) {
            0 -> FWD_NAME_POOL162[rnd.nextInt(FWD_NAME_POOL162.size)]
            else -> randomFwdName162(rnd)
        }
        val nickname = when (rnd.nextInt(10)) {
            0 -> null
            1 -> FWD_NICK_POOL162[rnd.nextInt(FWD_NICK_POOL162.size)]
            else -> randomFwdName162(rnd)
        }
        Triple(id, name, nickname)
    }
    val currentUserId = if (rnd.nextInt(10) == 0) "me" else randomFwdName162(rnd)
    return FwdCase162(isGroup, groupName, participants, currentUserId)
}

internal fun fwdJsonEscape162(s: String): String {
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

internal fun fwdWireJson162(case: FwdCase162): String {
    val sb = StringBuilder()
    sb.append("{\"isGroup\":").append(case.isGroup)
    sb.append(",\"groupName\":")
    if (case.groupName == null) sb.append("null")
    else sb.append("\"").append(fwdJsonEscape162(case.groupName)).append("\"")
    sb.append(",\"participants\":[")
    case.participants.forEachIndexed { index, p ->
        if (index > 0) sb.append(",")
        sb.append("{\"id\":\"").append(fwdJsonEscape162(p.first)).append("\"")
        sb.append(",\"name\":\"").append(fwdJsonEscape162(p.second)).append("\"")
        sb.append(",\"nickname\":")
        val nick = p.third
        if (nick == null) sb.append("null")
        else sb.append("\"").append(fwdJsonEscape162(nick)).append("\"")
        sb.append("}")
    }
    sb.append("],\"currentUserId\":\"").append(fwdJsonEscape162(case.currentUserId)).append("\"}")
    return sb.toString()
}

class ForwardTargetNamePolicyFuzzTest {

    private fun encode(t: ForwardTargetName): String = when (t) {
        is ForwardTargetName.GroupNamed -> "GN:" + t.name
        is ForwardTargetName.GroupCount -> "GC:" + t.count
        is ForwardTargetName.Private -> "P:" + t.name
        ForwardTargetName.PrivateFallback -> "PF"
    }

    private fun check(case: FwdCase162) {
        val chat = Chat(
            id = "c",
            participants = case.participants.map {
                User(id = it.first, name = it.second, nickname = it.third)
            },
            isGroup = case.isGroup,
            groupName = case.groupName,
        )
        assertEquals(
            resolveOracle162(case.isGroup, case.groupName, case.participants, case.currentUserId),
            encode(ForwardTargetNamePolicy.resolve(chat, case.currentUserId)),
        )
    }

    @Test
    fun pinnedBoundaries() {
        assertEquals(
            ForwardTargetName.GroupNamed("Dev Team"),
            ForwardTargetNamePolicy.resolve(Chat(id = "g", isGroup = true, groupName = "Dev Team"), "me"),
        )
        assertEquals(
            ForwardTargetName.GroupNamed(""),
            ForwardTargetNamePolicy.resolve(Chat(id = "g", isGroup = true, groupName = ""), "me"),
        )
        assertEquals(
            ForwardTargetName.GroupCount(0),
            ForwardTargetNamePolicy.resolve(Chat(id = "g", isGroup = true, groupName = null), "me"),
        )
        assertEquals(
            ForwardTargetName.PrivateFallback,
            ForwardTargetNamePolicy.resolve(Chat(id = "d", isGroup = false), "me"),
        )
        // Group name wins verbatim, even blank; null falls back to the count.
        check(FwdCase162(true, "Dev Team", emptyList(), "me"))
        check(FwdCase162(true, "", emptyList(), "me"))
        check(FwdCase162(true, " ", emptyList(), "me"))
        check(FwdCase162(true, null, emptyList(), "me"))
        check(FwdCase162(true, null, List(3) { Triple("u$it", "u$it", null) }, "me"))
        // First non-self peer wins; nickname beats name beats id.
        check(FwdCase162(false, null, listOf(Triple("me", "Me", null), Triple("peer", "Alice", null)), "me"))
        check(FwdCase162(false, null, listOf(Triple("me", "Me", "Mimi"), Triple("peer", "Alice", null)), "me"))
        check(FwdCase162(false, null, listOf(Triple("peer", "Alice", "  ")), "me"))
        check(FwdCase162(false, null, listOf(Triple("peer", "  ", " ")), "me"))
        check(FwdCase162(false, null, listOf(Triple("peer", "", null)), "me"))
        check(FwdCase162(false, null, listOf(Triple("peer", "", "Nick")), "me"))
        check(FwdCase162(false, null, listOf(Triple("me", "Me", null)), "me"))
        check(FwdCase162(false, null, emptyList(), "me"))
        // Duplicate ids, self in the middle, blank-but-not-empty names.
        check(FwdCase162(false, null, listOf(Triple("me", "A", null), Triple("me", "B", null), Triple("u", "\u00a0", null)), "me"))
        check(FwdCase162(false, null, listOf(Triple("u", "\u0085", null)), "me"))
        check(FwdCase162(false, null, listOf(Triple("u", "\u200b", null)), "me"))
        check(FwdCase162(false, null, listOf(Triple("me", "A", "  "), Triple("u", "B", "Nick")), "me"))
        for (groupName in FWD_GROUP_POOL162) {
            check(FwdCase162(true, groupName, emptyList(), "me"))
        }
        for (name in FWD_NAME_POOL162) {
            check(FwdCase162(false, null, listOf(Triple("peer", name, null)), "me"))
        }
        for (nick in FWD_NICK_POOL162) {
            check(FwdCase162(false, null, listOf(Triple("peer", "Alice", nick)), "me"))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(162001)
        repeat(2000) {
            check(randomFwdCase162(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every single-participant shape against both chat kinds.
        val ids = listOf("me", "u1", "u2", "")
        val names = listOf("", " ", "Alice", "  ", "\u00a0", "\u0085")
        val nicks: List<String?> = listOf(null, "", " ", "Nick", "\u00a0")
        for (groupName in listOf<String?>(null, "", "g")) {
            for (isGroup in listOf(true, false)) {
                check(FwdCase162(isGroup, groupName, emptyList(), "me"))
                for (id in ids) {
                    for (name in names) {
                        for (nick in nicks) {
                            check(FwdCase162(isGroup, groupName, listOf(Triple(id, name, nick)), "me"))
                        }
                    }
                }
            }
        }
        // Two participants: first-self / first-peer / both-self / duplicate ids.
        val pairs = listOf(
            listOf(Triple("me", "A", null), Triple("u", "B", null)),
            listOf(Triple("u", "B", null), Triple("me", "A", null)),
            listOf(Triple("me", "A", null), Triple("me", "B", null)),
            listOf(Triple("u", "B", "N1"), Triple("u", "C", "N2")),
        )
        for (pair in pairs) {
            check(FwdCase162(false, null, pair, "me"))
            check(FwdCase162(true, null, pair, "me"))
        }
        // Every UTF-16 unit as nickname / name / id of the peer.
        for (u in 0..0xFFFF) {
            val c = u.toChar().toString()
            check(FwdCase162(false, null, listOf(Triple("peer", "Alice", c)), "me"))
            check(FwdCase162(false, null, listOf(Triple("peer", c, null)), "me"))
            check(FwdCase162(false, null, listOf(Triple(c, "Alice", null)), "me"))
        }
        // Alphabet pairs as names.
        for (a in FWD_ALPHABET162) {
            for (b in FWD_ALPHABET162) {
                check(FwdCase162(false, null, listOf(Triple("peer", a.toString() + b, null)), "me"))
            }
        }
    }
}
