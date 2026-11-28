package com.maodouchat.domain.contacts

import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [BlockedUsersFilterPolicy] (checklist line 931, 36th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the name / id search from the documented rules. Note
 * that matches() does not trim the query (filter() does), and that email,
 * status and the other UserDto fields never participate in matching. A Python
 * mini-model differentially checks both oracles on 5000 random inputs each
 * (0 diffs) before this file was written.
 */
class BlockedUsersFilterPolicyFuzzTest {

    private fun oracleMatches(user: UserDto, query: String): Boolean =
        user.name.contains(query, ignoreCase = true) ||
            user.id.contains(query, ignoreCase = true)

    private fun oracleFilter(users: List<UserDto>, query: String): List<UserDto> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return users
        return users.filter { oracleMatches(it, trimmed) }
    }

    private val ids = listOf("u1", "u2", "alice", "Bob", "张三", "x_9", "")
    private val names = listOf("Alice", "bob", "张三", "", "李四", "Al", "  ")
    private val emails = listOf("alice@example.com", "zzz@qq.com", "", "bob@x.io")
    private val statuses = listOf("online", "busy", "", "away")

    private fun randomUser(rnd: Random): UserDto =
        UserDto(
            id = ids.random(rnd),
            name = names.random(rnd),
            email = emails.random(rnd),
            status = statuses.random(rnd),
            username = listOf<String?>(null, "alice", "bob99").random(rnd)
        )

    private fun randomSubstring(rnd: Random, s: String): String {
        if (s.isEmpty()) return ""
        val start = rnd.nextInt(s.length)
        return s.substring(start, rnd.nextInt(start, s.length + 1))
    }

    private fun randomQuery(rnd: Random, user: UserDto): String {
        val fields = listOf(user.name, user.id, user.email, user.status)
        val query = when (rnd.nextInt(10)) {
            in 0..4 -> randomSubstring(rnd, fields.random(rnd))
            5 -> listOf("alice", "ALICE", "bob", "张", "u1", "qq.com", "online").random(rnd)
            6 -> ""
            7 -> "   "
            8 -> "zzz"
            else -> listOf("  alice  ", " ALI", "bob ", "  ").random(rnd)
        }
        return when (rnd.nextInt(4)) {
            0 -> query.uppercase()
            1 -> query.lowercase()
            else -> query
        }
    }

    @Test
    fun matchesRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val user = randomUser(rnd)
            val query = randomQuery(rnd, user)
            assertEquals(
                oracleMatches(user, query),
                BlockedUsersFilterPolicy.matches(user, query),
                "user=$user query=<$query>"
            )
        }
    }

    @Test
    fun filterRandomOracle() {
        val rnd = Random(36)
        repeat(200) {
            val users = List(rnd.nextInt(0, 7)) { randomUser(rnd) }
            val query = if (users.isEmpty()) randomQuery(rnd, randomUser(rnd))
                else randomQuery(rnd, users.random(rnd))
            assertEquals(
                oracleFilter(users, query),
                BlockedUsersFilterPolicy.filter(users, query),
                "query=<$query>"
            )
        }
    }

    @Test
    fun otherFieldsNeverMatch() {
        val rnd = Random(36)
        // The query only appears in email / status / username; name and id
        // never contain it, so matches() must stay false.
        val noMatchQueries = listOf("zzz", "qq.com", "@example", "999", "online")
        repeat(200) {
            val query = noMatchQueries.random(rnd)
            val user = UserDto(
                id = "u1",
                name = "Alice",
                email = query,
                status = query,
                username = query
            )
            assertFalse(
                BlockedUsersFilterPolicy.matches(user, query),
                "other fields must not match: user=$user query=<$query>"
            )
            assertFalse(oracleMatches(user, query), "oracle agrees: query=<$query>")
        }
    }

    @Test
    fun filterSubsequenceInvariant() {
        val rnd = Random(36)
        repeat(200) {
            val users = List(rnd.nextInt(0, 7)) { randomUser(rnd) }
            val query = if (users.isEmpty()) "alice" else randomQuery(rnd, users.random(rnd))
            val result = BlockedUsersFilterPolicy.filter(users, query)
            var cursor = 0
            for (user in result) {
                val found = users.subList(cursor, users.size).indexOf(user)
                assertTrue(found >= 0, "result not a subsequence: query=<$query>")
                cursor += found + 1
            }
            val trimmed = query.trim()
            for (user in result) {
                assertTrue(oracleMatches(user, trimmed), "result must match: query=<$query>")
            }
            if (trimmed.isBlank()) assertEquals(users, result, "blank query returns all")
        }
    }

    @Test
    fun pins() {
        val u1 = UserDto(id = "u1", name = "Alice", email = "alice@example.com", status = "online")
        assertTrue(BlockedUsersFilterPolicy.matches(u1, "alice"), "name ignoreCase")
        assertTrue(BlockedUsersFilterPolicy.matches(u1, "U1"), "id ignoreCase")
        assertFalse(BlockedUsersFilterPolicy.matches(u1, "example.com"), "email never matches")
        assertFalse(BlockedUsersFilterPolicy.matches(u1, "online"), "status never matches")
        assertTrue(BlockedUsersFilterPolicy.matches(u1, ""), "empty query matches everything")
        val u2 = UserDto(id = "u2", name = "Bob", email = "zzz@qq.com")
        assertFalse(BlockedUsersFilterPolicy.matches(u2, "qq.com"), "email only, no match")
        assertTrue(BlockedUsersFilterPolicy.matches(u2, "Bob"), "name matches")
        val users = listOf(u1, u2)
        assertEquals(users, BlockedUsersFilterPolicy.filter(users, "  "), "blank query returns all")
        assertEquals(listOf(u1), BlockedUsersFilterPolicy.filter(users, "  ALI  "), "filter trims query")
        assertEquals(emptyList(), BlockedUsersFilterPolicy.filter(users, "qq.com"), "email query matches nobody")
    }
}
