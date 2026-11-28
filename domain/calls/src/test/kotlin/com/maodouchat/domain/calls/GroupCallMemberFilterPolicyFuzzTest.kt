package com.maodouchat.domain.calls

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupCallMemberFilterPolicyFuzzTest {

    private val random = Random(0x6F17_2026L)

    private val alphabet = "abcXYZ019 _-äÄßéü张李"

    private fun randomString(maxLength: Int): String {
        val length = random.nextInt(0, maxLength + 1)
        return buildString {
            repeat(length) { append(alphabet.random(random)) }
        }
    }

    // displayName 三级回退的逐字复述：nickname 非空白 → name 非空白 → id。
    private fun oracleDisplayName(user: User): String =
        user.nickname?.takeIf(String::isNotBlank)
            ?: user.name.takeIf(String::isNotBlank)
            ?: user.id

    private fun oracleMatches(user: User, query: String): Boolean =
        oracleDisplayName(user).contains(query, ignoreCase = true) ||
            user.id.contains(query, ignoreCase = true)

    private fun randomUser(index: Int): User {
        // 有意混入空串与空白串，覆盖三级回退的每一支
        val nickname = when (random.nextInt(4)) {
            0 -> null
            1 -> ""
            2 -> "   "
            else -> randomString(10)
        }
        return User(
            id = "u$index-${randomString(6)}",
            name = if (random.nextInt(5) == 0) "" else randomString(10),
            nickname = nickname,
        )
    }

    private fun randomQuery(users: List<User>): String {
        return when (random.nextInt(4)) {
            // 取某人的 displayName 或 id 的子串：大概率命中
            0 -> {
                val source = users.randomOrNull()?.let {
                    if (random.nextBoolean()) oracleDisplayName(it) else it.id
                }.orEmpty()
                if (source.isEmpty()) randomString(4) else {
                    val start = random.nextInt(0, source.length)
                    val end = random.nextInt(start, source.length + 1)
                    var sub = source.substring(start, end)
                    // 大小写翻转：命中应与大小写无关
                    if (random.nextBoolean()) sub = sub.uppercase()
                    sub
                }
            }
            1 -> " ".repeat(random.nextInt(0, 3))
            2 -> ""
            else -> randomString(6)
        }
    }

    @Test
    fun `matches replicates displayName fallback and contains`() {
        repeat(200) {
            val user = randomUser(it)
            val query = randomQuery(listOf(user))
            assertEquals(
                oracleMatches(user, query),
                GroupCallMemberFilterPolicy.matches(user, query),
                "user=$user query='$query'",
            )
        }
    }

    @Test
    fun `filter returns the ordered matching subsequence`() {
        repeat(200) {
            val candidates = List(random.nextInt(0, 7)) { i -> randomUser(i) }
            val query = randomQuery(candidates)
            val trimmed = query.trim()
            val expected = if (trimmed.isBlank()) {
                candidates
            } else {
                candidates.filter { oracleMatches(it, trimmed) }
            }
            val actual = GroupCallMemberFilterPolicy.filter(candidates, query)
            assertEquals(expected, actual, "candidates=$candidates query='$query'")
            // 不变量：结果恒为原列表的子序列（保序、无新增）
            var cursor = 0
            for (kept in actual) {
                val found = candidates.subList(cursor, candidates.size).indexOf(kept)
                assertTrue(found >= 0, "kept=$kept candidates=$candidates query='$query'")
                cursor += found + 1
            }
        }
    }

    @Test
    fun `blank query returns the same list`() {
        repeat(50) {
            val candidates = List(random.nextInt(0, 6)) { i -> randomUser(i) }
            val blank = listOf("", " ", "   ", "\t ").random(random)
            assertEquals(
                candidates,
                GroupCallMemberFilterPolicy.filter(candidates, blank),
                "candidates=$candidates query='$blank'",
            )
        }
    }

    @Test
    fun `query is trimmed before matching`() {
        repeat(100) {
            val candidates = List(random.nextInt(1, 6)) { i -> randomUser(i) }
            val query = randomQuery(candidates)
            val padded = "  $query\t "
            assertEquals(
                GroupCallMemberFilterPolicy.filter(candidates, query),
                GroupCallMemberFilterPolicy.filter(candidates, padded),
                "candidates=$candidates query='$query'",
            )
        }
    }

    @Test
    fun `displayName fallback branches are exercised`() {
        // nickname 优先
        val nicknamed = User(id = "u1", name = "Name", nickname = "Nick")
        assertEquals("Nick", oracleDisplayName(nicknamed))
        assertTrue(GroupCallMemberFilterPolicy.matches(nicknamed, "nick"))
        assertTrue(!GroupCallMemberFilterPolicy.matches(nicknamed, "name"))
        // nickname 为空白时退到 name
        val blankNick = User(id = "u2", name = "Name", nickname = "  ")
        assertEquals("Name", oracleDisplayName(blankNick))
        assertTrue(GroupCallMemberFilterPolicy.matches(blankNick, "name"))
        // nickname 与 name 皆空白时退到 id
        val idOnly = User(id = "u3-fallback", name = "", nickname = null)
        assertEquals("u3-fallback", oracleDisplayName(idOnly))
        assertTrue(GroupCallMemberFilterPolicy.matches(idOnly, "fallback"))
        // name 为空白、nickname 为空时同样退到 id
        val emptyName = User(id = "u4", name = " ", nickname = "")
        assertEquals("u4", oracleDisplayName(emptyName))
    }
}
