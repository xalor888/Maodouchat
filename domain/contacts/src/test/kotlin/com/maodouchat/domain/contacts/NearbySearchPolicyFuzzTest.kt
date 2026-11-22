package com.maodouchat.domain.contacts

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [NearbySearchPolicy] (checklist line 931, 36th block).
 * Test-only, zero production changes.
 *
 * Note the two asymmetries the oracle must reproduce: matches() does not trim
 * the query (filter() does), and displayName prefers a non-blank nickname
 * over the name, falling back to the id. A Python mini-model differentially
 * checks both oracles on 5000 random inputs each (0 diffs) before this file
 * was written.
 */
class NearbySearchPolicyFuzzTest {

    private fun oracleDisplayName(user: User): String {
        val nickname = user.nickname
        if (nickname != null && nickname.isNotBlank()) return nickname
        if (user.name.isNotBlank()) return user.name
        return user.id
    }

    private fun oracleMatches(person: NearbyPerson, query: String): Boolean =
        oracleDisplayName(person.user).contains(query, ignoreCase = true) ||
            person.user.name.contains(query, ignoreCase = true) ||
            person.user.id.contains(query, ignoreCase = true) ||
            person.user.status.contains(query, ignoreCase = true)

    private fun oracleFilter(people: List<NearbyPerson>, query: String): List<NearbyPerson> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return people
        return people.filter { oracleMatches(it, trimmed) }
    }

    private val ids = listOf("u1", "u2", "alice", "Bob", "张三", "x_9", "")
    private val names = listOf("Alice", "bob", "张三", "", "李四", "Al", "  ")
    private val nicknames = listOf<String?>(null, "阿狸", "Ali", "", "  ", "bob")
    private val statuses = listOf("out for a walk", "at school", "", "busy", "在家", "OUT")

    private fun randomUser(rnd: Random): User =
        User(
            id = ids.random(rnd),
            name = names.random(rnd),
            status = statuses.random(rnd),
            nickname = nicknames.random(rnd)
        )

    private fun randomPerson(rnd: Random): NearbyPerson =
        NearbyPerson(randomUser(rnd), distanceMeters = rnd.nextInt(0, 5000), locationUpdatedAt = rnd.nextLong())

    private fun randomSubstring(rnd: Random, s: String): String {
        if (s.isEmpty()) return ""
        val start = rnd.nextInt(s.length)
        return s.substring(start, rnd.nextInt(start, s.length + 1))
    }

    private fun randomQuery(rnd: Random, person: NearbyPerson): String {
        val fields = listOf(
            oracleDisplayName(person.user), person.user.name,
            person.user.id, person.user.status
        )
        val query = when (rnd.nextInt(10)) {
            in 0..4 -> randomSubstring(rnd, fields.random(rnd))
            5 -> listOf("alice", "ALICE", "bob", "张", "u1", "walk", "OUT", "阿狸").random(rnd)
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
            val person = randomPerson(rnd)
            val query = randomQuery(rnd, person)
            assertEquals(
                oracleMatches(person, query),
                NearbySearchPolicy.matches(person, query),
                "person=${person.user} query=<$query>"
            )
        }
    }

    @Test
    fun filterRandomOracle() {
        val rnd = Random(36)
        repeat(200) {
            val people = List(rnd.nextInt(0, 7)) { randomPerson(rnd) }
            val query = if (people.isEmpty()) randomQuery(rnd, randomPerson(rnd))
                else randomQuery(rnd, people.random(rnd))
            assertEquals(
                oracleFilter(people, query),
                NearbySearchPolicy.filter(people, query),
                "query=<$query>"
            )
        }
    }

    @Test
    fun displayNamePrecedenceRandomOracle() {
        val rnd = Random(36)
        repeat(300) {
            val user = randomUser(rnd)
            assertEquals(oracleDisplayName(user), user.displayName, "user=$user")
        }
    }

    @Test
    fun filterSubsequenceInvariant() {
        val rnd = Random(36)
        repeat(200) {
            val people = List(rnd.nextInt(0, 7)) { randomPerson(rnd) }
            val query = if (people.isEmpty()) "alice" else randomQuery(rnd, people.random(rnd))
            val result = NearbySearchPolicy.filter(people, query)
            // Result is a subsequence of the input, in the same order.
            var cursor = 0
            for (person in result) {
                val found = people.subList(cursor, people.size).indexOf(person)
                assertTrue(found >= 0, "result not a subsequence: query=<$query>")
                cursor += found + 1
            }
            val trimmed = query.trim()
            for (person in result) {
                assertTrue(oracleMatches(person, trimmed), "result must match: query=<$query>")
            }
            if (trimmed.isBlank()) assertEquals(people, result, "blank query returns all")
        }
    }

    private fun person(id: String, name: String, status: String, nickname: String? = null) =
        NearbyPerson(User(id = id, name = name, status = status, nickname = nickname), 100, 0L)

    @Test
    fun pins() {
        val p1 = person("u1", "Alice", "out for a walk", "阿狸")
        assertTrue(NearbySearchPolicy.matches(p1, "阿狸"), "nickname is displayName")
        assertTrue(NearbySearchPolicy.matches(p1, "Alice"), "name still matches")
        assertTrue(NearbySearchPolicy.matches(p1, "ALICE"), "ignoreCase on name")
        assertTrue(NearbySearchPolicy.matches(p1, "u1"), "id matches")
        assertTrue(NearbySearchPolicy.matches(p1, "walk"), "status matches")
        assertTrue(NearbySearchPolicy.matches(p1, ""), "empty query matches everything")
        assertFalse(NearbySearchPolicy.matches(p1, "阿狸 "), "matches does not trim")
        assertFalse(NearbySearchPolicy.matches(p1, "  "), "double space in no field")
        val p2 = person("u2", "  ", "")
        assertTrue(NearbySearchPolicy.matches(p2, "u2"), "blank name falls back to id")
        val p3 = person("u3", "", "", "  ")
        assertTrue(NearbySearchPolicy.matches(p3, "u3"), "blank nickname falls back to id")
        assertEquals("阿狸", p1.user.displayName, "nickname wins")
        assertEquals("u2", p2.user.displayName, "name blank, id wins")
        val people = listOf(p1, p2, p3)
        assertEquals(people, NearbySearchPolicy.filter(people, "  "), "blank query returns all")
        assertEquals(listOf(p1), NearbySearchPolicy.filter(people, "  ali  "), "filter trims query")
        assertEquals(emptyList(), NearbySearchPolicy.filter(people, "zzz"), "no match")
        assertEquals(listOf(p1), NearbySearchPolicy.filter(people, "WALK"), "filter ignoreCase")
    }
}
