// ContactMemberCandidates fuzz (149th block). Oracle reimplements
// filterAndSort / selectedMembers from production text. The ignore-case
// match is per-unit regionMatchesCI (compare uppercase, then lowercase
// of the uppercased forms -- fuzz-132 finding); the sort key is
// displayName.lowercase(). Bare-name note: GifSearchPolicy has its own
// filterAndSort (different signature, fuzz-68) -- not this function.
package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------- oracle ----------

private fun eqIgnoreCaseOracle149(a: Char, b: Char): Boolean {
    if (a == b) return true
    val ua = a.uppercaseChar()
    val ub = b.uppercaseChar()
    if (ua == ub) return true
    return ua.lowercaseChar() == ub.lowercaseChar()
}

private fun containsIgnoreCaseOracle149(hay: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > hay.length) return false
    for (i in 0..hay.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!eqIgnoreCaseOracle149(hay[i + j], needle[j])) {
                ok = false
                break
            }
        }
        if (ok) return true
    }
    return false
}

private fun displayNameOracle149(user: User): String =
    user.nickname?.takeIf { it.isNotBlank() }
        ?: user.name.takeIf { it.isNotBlank() }
        ?: user.id

private fun filterAndSortOracle149(contacts: List<User>, query: String): List<User> {
    val q = query.trim()
    val base = if (q.isEmpty()) contacts
    else contacts.filter { user ->
        containsIgnoreCaseOracle149(displayNameOracle149(user), q) ||
            containsIgnoreCaseOracle149(user.name, q) ||
            containsIgnoreCaseOracle149(user.id, q) ||
            containsIgnoreCaseOracle149(user.email, q) ||
            (user.nickname?.let { containsIgnoreCaseOracle149(it, q) } == true)
    }
    return base.sortedWith(compareByDescending<User> { it.isOnline }.thenBy { displayNameOracle149(it).lowercase() })
}

private fun selectedMembersOracle149(contacts: List<User>, selectedIds: Set<String>): List<User> =
    contacts.filter { it.id in selectedIds }

// ---------- generators ----------

private val NAME_POOL149 = arrayOf(
    "", " ", "  ", "Alice", "alice", "ALICE", "Bob", "bob",
    "张三", "李四", "王小明", "aBc", "AbC", "ﬁsh", "ﬀ", "ß", "ﬀe",
    "İ", "ı", "I", "i", "ŉ", "ǅenana", "ǆenana",
    "  padded  ", "a b", "a-b", "a_b", "x@y", "Émile", "émile",
    "Σ", "ΟΣ", "σ", "ﬀoo", "ﬀOO", "ﬂower",
    "\uD800", "\uDFFF", "a\uD800b",
)

private val ID_POOL149 = arrayOf(
    "", "u1", "u2", "U1", "alice", "Alice", "a", "b", "ab", "  ", "x@y",
)

private val EMAIL_POOL149 = arrayOf(
    "", "a@b.c", "ALICE@x.com", "alice@x.com", "bob@example.org", "  ",
)

private val QUERY_POOL149 = arrayOf(
    "", " ", "  ", "a", "A", "al", "AL", "alice", "ALICE", "Ali",
    "张", "张三", "ß", "ss", "ﬀ", "ff", "ﬁ", "fi",
    "İ", "ı", "i", "I", "Σ", "σ", "ς", "ΟΣ", "os",
    "@", "x.com", "X.COM", "  a  ", "b", "z", "zz",
    "\uD800", "u1", "U1",
)

private fun randomUser149(rnd: Random): User {
    val id = ID_POOL149[rnd.nextInt(ID_POOL149.size)]
    val name = NAME_POOL149[rnd.nextInt(NAME_POOL149.size)]
    val nick = when (rnd.nextInt(4)) {
        0 -> null
        1 -> ""
        2 -> " "
        else -> NAME_POOL149[rnd.nextInt(NAME_POOL149.size)]
    }
    return User(
        id = id,
        name = name,
        email = EMAIL_POOL149[rnd.nextInt(EMAIL_POOL149.size)],
        isOnline = rnd.nextBoolean(),
        nickname = nick,
    )
}

private fun randomUsers149(rnd: Random): List<User> {
    val n = rnd.nextInt(0, 6)
    return (0 until n).map { randomUser149(rnd) }
}

private fun randomQuery149(rnd: Random): String =
    if (rnd.nextInt(2) == 0) QUERY_POOL149[rnd.nextInt(QUERY_POOL149.size)]
    else {
        val len = rnd.nextInt(0, 6)
        buildString {
            repeat(len) {
                when (rnd.nextInt(6)) {
                    0 -> append(('a'.code + rnd.nextInt(26)).toChar())
                    1 -> append(' ')
                    2 -> append(rnd.nextInt(0x20, 0x2FFF).toChar())
                    else -> append(QUERY_POOL149[rnd.nextInt(QUERY_POOL149.size)])
                }
            }
        }
    }

class ContactMemberCandidatesFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // displayName: non-blank nickname wins, then non-blank name, then id.
        assertEquals("Nick", displayNameOracle149(User(id = "u1", name = "Name", nickname = "Nick")))
        assertEquals("Name", displayNameOracle149(User(id = "u1", name = "Name", nickname = "  ")))
        assertEquals("Name", displayNameOracle149(User(id = "u1", name = "Name", nickname = "")))
        assertEquals("Name", displayNameOracle149(User(id = "u1", name = "Name", nickname = null)))
        assertEquals("u1", displayNameOracle149(User(id = "u1", name = "  ", nickname = null)))
        assertEquals("u1", displayNameOracle149(User(id = "u1", name = "", nickname = " ")))
        assertEquals(displayNameOracle149(User(id = "u1", name = "Name", nickname = "Nick")), User(id = "u1", name = "Name", nickname = "Nick").displayName)
        // Empty/blank query matches everything but still sorts.
        val users = listOf(
            User(id = "u2", name = "bob", isOnline = false),
            User(id = "u1", name = "Alice", isOnline = true),
        )
        assertEquals(listOf("u1", "u2"), ContactMemberCandidates.filterAndSort(users, "").map { it.id })
        assertEquals(listOf("u1", "u2"), ContactMemberCandidates.filterAndSort(users, "   ").map { it.id })
        assertEquals(filterAndSortOracle149(users, ""), ContactMemberCandidates.filterAndSort(users, ""))
        // Online first, then displayName.lowercase() ascending, stable.
        val mixed = listOf(
            User(id = "u3", name = "bob", isOnline = false),
            User(id = "u1", name = "Alice", isOnline = true),
            User(id = "u2", name = "alice", isOnline = true),
            User(id = "u4", name = "Bob", isOnline = false),
        )
        val sorted = ContactMemberCandidates.filterAndSort(mixed, "  ")
        assertEquals(listOf("u1", "u2", "u3", "u4"), sorted.map { it.id })
        assertEquals(filterAndSortOracle149(mixed, "  "), sorted)
        // Each field matches: displayName, name, id, email, nickname.
        val one = User(id = "u9", name = "Zhang", nickname = "Zed", email = "z@x.com")
        assertEquals(listOf(one), ContactMemberCandidates.filterAndSort(listOf(one), "zed"))
        assertEquals(listOf(one), ContactMemberCandidates.filterAndSort(listOf(one), "ZHANG"))
        assertEquals(listOf(one), ContactMemberCandidates.filterAndSort(listOf(one), "u9"))
        assertEquals(listOf(one), ContactMemberCandidates.filterAndSort(listOf(one), "Z@X"))
        assertEquals(emptyList(), ContactMemberCandidates.filterAndSort(listOf(one), "qqq"))
        assertEquals(filterAndSortOracle149(listOf(one), "zed"), ContactMemberCandidates.filterAndSort(listOf(one), "zed"))
        // Nickname only matches when it is the query hit (name/id miss).
        val nickOnly = User(id = "u1", name = "aaa", nickname = "zzz")
        assertEquals(listOf(nickOnly), ContactMemberCandidates.filterAndSort(listOf(nickOnly), "ZZZ"))
        // Ignore-case: ı matches İ, ß does not match ss, ﬀ does not match ff.
        assertTrue(containsIgnoreCaseOracle149("ı", "İ"))
        assertTrue(containsIgnoreCaseOracle149("İ", "ı"))
        assertEquals(false, containsIgnoreCaseOracle149("ß", "ss"))
        assertEquals(false, containsIgnoreCaseOracle149("ss", "ß"))
        assertEquals(false, containsIgnoreCaseOracle149("ﬀ", "ff"))
        assertEquals(
            containsIgnoreCaseOracle149("Alice", "AL"),
            "Alice".contains("AL", ignoreCase = true),
        )
        // selectedMembers keeps list order and drops unknown ids.
        val sel = ContactMemberCandidates.selectedMembers(mixed, setOf("u2", "u9", "u1"))
        assertEquals(listOf("u1", "u2"), sel.map { it.id })
        assertEquals(selectedMembersOracle149(mixed, setOf("u2", "u9", "u1")), sel)
        assertEquals(emptyList(), ContactMemberCandidates.selectedMembers(mixed, emptySet()))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(149002)
        repeat(1500) {
            val contacts = randomUsers149(rnd)
            val query = randomQuery149(rnd)
            assertEquals(
                filterAndSortOracle149(contacts, query),
                ContactMemberCandidates.filterAndSort(contacts, query),
            )
            val ids = (0 until rnd.nextInt(0, 4)).map { ID_POOL149[rnd.nextInt(ID_POOL149.size)] }.toSet()
            assertEquals(
                selectedMembersOracle149(contacts, ids),
                ContactMemberCandidates.selectedMembers(contacts, ids),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Query pool x single-user name pool.
        val base = User(id = "u1", name = "Alice", email = "alice@x.com", nickname = "Al")
        for (name in NAME_POOL149) {
            val u = base.copy(name = name)
            for (query in QUERY_POOL149) {
                assertEquals(
                    filterAndSortOracle149(listOf(u), query),
                    ContactMemberCandidates.filterAndSort(listOf(u), query),
                )
            }
        }
        // Every pool query against a fixed multi-user list.
        val users = listOf(
            User(id = "u1", name = "Alice", email = "alice@x.com", isOnline = true),
            User(id = "u2", name = "bob", email = "BOB@x.com", isOnline = false, nickname = "Bobby"),
            User(id = "u3", name = "张三", email = "", isOnline = true),
            User(id = "u4", name = "  ", email = "x@y", isOnline = false),
        )
        for (query in QUERY_POOL149) {
            assertEquals(
                filterAndSortOracle149(users, query),
                ContactMemberCandidates.filterAndSort(users, query),
            )
        }
        // Single-unit queries against fixed names (ignore-case edge).
        val units = (0..0x7F).toList() +
            listOf(0x80, 0x85, 0xA0, 0xFF, 0x100, 0x130, 0x131, 0x149, 0x17F, 0x1F88, 0x1FBC, 0x3A3, 0x3C2, 0x3C3, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF, 0x2000, 0x2028, 0x2029, 0x3000)
        for (u in units) {
            val q = String(charArrayOf(u.toChar()))
            assertEquals(
                filterAndSortOracle149(users, q),
                ContactMemberCandidates.filterAndSort(users, q),
            )
        }
        // Single-unit display names through the sort key.
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            val pair = listOf(
                User(id = "a", name = ch, isOnline = false),
                User(id = "b", name = "m", isOnline = false),
            )
            assertEquals(
                filterAndSortOracle149(pair, ""),
                ContactMemberCandidates.filterAndSort(pair, ""),
            )
        }
        // selectedMembers: id pool subsets.
        for (id in ID_POOL149) {
            assertEquals(
                selectedMembersOracle149(users, setOf(id)),
                ContactMemberCandidates.selectedMembers(users, setOf(id)),
            )
        }
    }
}
