// Fuzz / random-oracle tests for FriendRequestFilterPolicy
// (checklist line 931, 157th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a request
// matches when the display name (nickname if non-blank, else name if
// non-blank, else id), the name, the id, or the message contains the
// query ignoring case; filter trims the query and returns everything
// when it is blank. Case-insensitive containment is the JDK
// regionMatches rule (upper, else the lower-of-upper fold -- probed per
// code unit: İ equals ı and I, ß equals ẞ, K equals the Kelvin sign);
// the oracle calls the same stdlib contains the production does, so the
// two cannot drift on case mapping.
package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun friendDisplayNameOracle157(nickname: String?, name: String, id: String): String =
    nickname?.takeIf(String::isNotBlank) ?: name.takeIf(String::isNotBlank) ?: id

internal fun friendMatchesOracle157(
    nickname: String?,
    name: String,
    id: String,
    message: String,
    query: String,
): Boolean {
    val display = friendDisplayNameOracle157(nickname, name, id)
    return display.contains(query, ignoreCase = true) ||
        name.contains(query, ignoreCase = true) ||
        id.contains(query, ignoreCase = true) ||
        message.contains(query, ignoreCase = true)
}

internal fun friendFilterOracle157(requests: List<FriendRequestItem>, query: String): List<FriendRequestItem> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return requests
    return requests.filter {
        friendMatchesOracle157(it.user.nickname, it.user.name, it.user.id, it.message, trimmed)
    }
}

// ---------- generators ----------

internal val FRIEND_WORD_POOL157 = listOf(
    "hello", "HELLO", "HeLLo", "world", "a", "A", "", " ", "q1", "q2",
    "Σ", "σ", "ς", "ΟΣ", "ος", "οσ", "İ", "ı", "I", "i", "ß", "ẞ",
    "K", "K", "k", "ſ", "s", "S", "µ", "μ", "Μ", "Ǆ", "ǅ", "ǆ",
    "Ÿ", "ÿ", "é", "É", "ﬀ", "张三", "群名", "\u00a0", "\u0085",
    "Alice", "ALICE", "alice", "Bob", "bob", "Al", "ali",
)

internal val FRIEND_ALPHABET157 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003ΣσςİıIißẞK\u212a kſsSµμΜǄǅǆŸÿéÉ\uFB00"

internal fun randomFriendChar157(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> FRIEND_ALPHABET157[rnd.nextInt(FRIEND_ALPHABET157.length)].toString()
}

internal fun randomFriendString157(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomFriendChar157(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal fun randomFriendUser157(rnd: Random): User = User(
    id = if (rnd.nextInt(10) < 6) FRIEND_WORD_POOL157[rnd.nextInt(FRIEND_WORD_POOL157.size)] else randomFriendString157(rnd),
    name = if (rnd.nextInt(10) < 6) FRIEND_WORD_POOL157[rnd.nextInt(FRIEND_WORD_POOL157.size)] else randomFriendString157(rnd),
    nickname = if (rnd.nextInt(10) < 3) null
        else if (rnd.nextInt(10) < 6) FRIEND_WORD_POOL157[rnd.nextInt(FRIEND_WORD_POOL157.size)]
        else randomFriendString157(rnd),
)

internal fun randomFriendRequest157(rnd: Random, id: String): FriendRequestItem =
    FriendRequestItem(id = id, user = randomFriendUser157(rnd), message = randomFriendString157(rnd))

internal data class FriendCase157(
    val requests: List<FriendRequestItem>,
    val query: String,
)

internal fun randomFriendCase157(rnd: Random): FriendCase157 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    val requests = List(count) { i -> randomFriendRequest157(rnd, "r$i") }
    val query = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> if (requests.isNotEmpty()) {
            // Often re-query an existing field (possibly case-shifted).
            val base = requests[rnd.nextInt(requests.size)]
            val field = when (rnd.nextInt(4)) {
                0 -> friendDisplayNameOracle157(base.user.nickname, base.user.name, base.user.id)
                1 -> base.user.name
                2 -> base.user.id
                else -> base.message
            }
            when (rnd.nextInt(3)) {
                0 -> field
                1 -> field.uppercase()
                else -> field.lowercase()
            }
        } else {
            randomFriendString157(rnd)
        }
        else -> randomFriendString157(rnd)
    }
    return FriendCase157(requests, query)
}

internal fun friendJsonEscape157(s: String): String {
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

internal fun friendUserJson157(user: User): String {
    val sb = StringBuilder()
    val nickname = user.nickname
    sb.append("{\"id\":\"").append(friendJsonEscape157(user.id)).append("\"")
    sb.append(",\"name\":\"").append(friendJsonEscape157(user.name)).append("\"")
    if (nickname != null) {
        sb.append(",\"nick\":\"").append(friendJsonEscape157(nickname)).append("\"")
    }
    sb.append("}")
    return sb.toString()
}

internal fun friendRequestJson157(request: FriendRequestItem): String =
    "{\"id\":\"" + friendJsonEscape157(request.id) + "\",\"user\":" +
        friendUserJson157(request.user) + ",\"msg\":\"" +
        friendJsonEscape157(request.message) + "\"}"

internal fun friendMatchesWireJson157(request: FriendRequestItem, query: String): String =
    friendRequestJson157(request).dropLast(1) + ",\"q\":\"" + friendJsonEscape157(query) + "\"}"

internal fun friendFilterWireJson157(case: FriendCase157): String {
    val sb = StringBuilder()
    sb.append("{\"reqs\":[")
    case.requests.forEachIndexed { i, r ->
        if (i > 0) sb.append(",")
        sb.append(friendRequestJson157(r))
    }
    sb.append("],\"q\":\"").append(friendJsonEscape157(case.query)).append("\"}")
    return sb.toString()
}

class FriendRequestFilterPolicyFuzzTest {

    private fun check(requests: List<FriendRequestItem>, query: String) {
        for (request in requests) {
            assertEquals(
                friendMatchesOracle157(request.user.nickname, request.user.name, request.user.id, request.message, query),
                FriendRequestFilterPolicy.matches(request, query),
            )
        }
        assertEquals(
            friendFilterOracle157(requests, query),
            FriendRequestFilterPolicy.filter(requests, query),
        )
    }

    @Test
    fun pinnedBoundaries() {
        val alice = FriendRequestItem(
            id = "u1",
            user = User(id = "u1", name = "Alice", nickname = "Al"),
            message = "hello world",
        )
        val bob = FriendRequestItem(
            id = "u2",
            user = User(id = "u2", name = "Bob", nickname = "   "),
            message = "",
        )
        val blank = FriendRequestItem(
            id = "u3",
            user = User(id = "u3", name = "", nickname = null),
            message = "",
        )
        // Display name prefers a non-blank nickname, then name, then id.
        assertEquals("Al", friendDisplayNameOracle157("Al", "Alice", "u1"))
        assertEquals("Bob", friendDisplayNameOracle157("   ", "Bob", "u2"))
        assertEquals("u3", friendDisplayNameOracle157(null, "", "u3"))
        assertEquals("u3", friendDisplayNameOracle157(null, "   ", "u3"))
        // (request, query, expected matches)
        val matchCases = listOf(
            Triple(alice, "al", true),
            Triple(alice, "AL", true),
            Triple(alice, "ALICE", true),
            Triple(alice, "alice", true),
            Triple(alice, "u1", true),
            Triple(alice, "HELLO", true),
            Triple(alice, "WORLD", true),
            Triple(alice, "zzz", false),
            Triple(alice, "", true),
            Triple(alice, " ", true),
            Triple(bob, "bob", true),
            Triple(bob, " ", false),
            Triple(bob, "u2", true),
            Triple(blank, "u3", true),
            Triple(blank, "", true),
            Triple(blank, "x", false),
            // Case-fold pairs (regionMatches): sigma, İ/ı, ß/ẞ, K/Kelvin.
            Triple(FriendRequestItem("s1", User("s1", "ΟΣ"), ""), "ος", true),
            Triple(FriendRequestItem("s1", User("s1", "ΟΣ"), ""), "οσ", true),
            Triple(FriendRequestItem("s1", User("s1", "İ"), ""), "ı", true),
            Triple(FriendRequestItem("s1", User("s1", "ß"), ""), "ẞ", true),
            Triple(FriendRequestItem("s1", User("s1", "K"), ""), "K", true),
            Triple(FriendRequestItem("s1", User("s1", "ﬀ"), ""), "FF", false),
        )
        for ((request, query, expected) in matchCases) {
            assertEquals(
                expected,
                friendMatchesOracle157(request.user.nickname, request.user.name, request.user.id, request.message, query),
            )
            assertEquals(expected, FriendRequestFilterPolicy.matches(request, query))
        }
        // (requests, query, expected ids in order)
        val filterCases = listOf(
            Triple(listOf(alice, bob), "ali", listOf("u1")),
            Triple(listOf(alice, bob), "ALI", listOf("u1")),
            Triple(listOf(alice, bob), "  ", listOf("u1", "u2")),
            Triple(listOf(alice, bob), "", listOf("u1", "u2")),
            Triple(listOf(alice, bob), "HELLO", listOf("u1")),
            Triple(listOf(alice, bob), "zzz", listOf()),
            Triple(listOf(alice, bob), "  bob  ", listOf("u2")),
            Triple(listOf(bob, alice), "u", listOf("u2", "u1")),
            Triple(listOf(), "ali", listOf()),
        )
        for ((requests, query, expectedIds) in filterCases) {
            assertEquals(expectedIds, friendFilterOracle157(requests, query).map { it.id })
            assertEquals(expectedIds, FriendRequestFilterPolicy.filter(requests, query).map { it.id })
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(157003)
        repeat(2000) {
            val case = randomFriendCase157(rnd)
            check(case.requests, case.query)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every word as a name/id/message field against every word query.
        for (field in FRIEND_WORD_POOL157) {
            val request = FriendRequestItem("w", User("w", field), field)
            for (query in FRIEND_WORD_POOL157) {
                assertEquals(
                    friendMatchesOracle157(null, field, "w", field, query),
                    FriendRequestFilterPolicy.matches(request, query),
                )
            }
        }
        // Fixed list against every word query (filter path).
        val list = listOf(
            FriendRequestItem("a", User("a", "Alice", nickname = "Al"), "hello"),
            FriendRequestItem("b", User("b", "Bob"), "world"),
            FriendRequestItem("c", User("c", "ΟΣ"), "ß"),
        )
        for (query in FRIEND_WORD_POOL157) {
            assertEquals(
                friendFilterOracle157(list, query),
                FriendRequestFilterPolicy.filter(list, query),
            )
        }
        // Blank-ish queries return the list untouched (NBSP counts as
        // whitespace; NEL does not -- it filters to nothing instead).
        for (query in listOf("", " ", "  \t ", "\u00a0")) {
            assertEquals(list, friendFilterOracle157(list, query))
            assertEquals(list, FriendRequestFilterPolicy.filter(list, query))
        }
        assertEquals(emptyList(), friendFilterOracle157(list, "\u0085"))
        assertEquals(emptyList(), FriendRequestFilterPolicy.filter(list, "\u0085"))
    }
}
