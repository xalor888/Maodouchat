package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Fuzz / random-oracle tests for ExploreDraftPolicy + the top-level
// normalizeVisibility (checklist line 931, 52nd block). Test-only, zero
// production changes. Pinned quirk: the draft falls back to PRIVATE while
// the settings page falls back to PUBLIC (G179, undecided in production);
// the oracle copies the draft direction and never touches production.
class ExploreDraftPolicyFuzzTest {

// ---------- B3 oracles: ExploreDraft ----------

private fun scopedKeyOracle(base: String, userId: String): String? {
    if (userId.isBlank()) return null
    return "$base:$userId"
}

private fun normalizeVisibilityOracle(value: String, fallback: String): String =
    // Hardcoded set on purpose: the oracle must not read production's
    // VISIBILITY_VALUES, or a drift there would go unnoticed.
    value.takeIf { it in setOf("PUBLIC", "CONTACTS", "PRIVATE") } ?: fallback

private fun exploreDraftNormalizeVisibilityOracle(value: String): String =
    normalizeVisibilityOracle(value, "PRIVATE")

// ---------- generators (Random(42) in the CI tests) ----------

private val STR_POOL = listOf(
    "", " ", "  ", "   a   ", "a", "ab", "abc", "|", "^", "\u0001", "\u0002",
    "a|b", "a^b", "a\u0001b", "a\u0002b", "||", "^^", "a|b^c",
    "hello", "hello world", "  padded  ", "\t", "\n", "a\tb", "a\nb",
    "é", "ß", "İ", "i", "I", "中文测试", "😀", "a😀b", "😀😀😀",
    "\u00a0", "x\u00a0y", "\u2007", "\u202f",
    "rock", "paper", "scissors", "ROCK", "Heads", "HEADS", "heads", "TAILS",
    "0", "1", "-1", "42", "9999999999", "2147483647", "-2147483648",
    "a".repeat(50), "x".repeat(130), "word ".repeat(30),
    "PUBLIC", "CONTACTS", "PRIVATE", "FRIENDS", "public", "private"
)

private val CH_POOL = listOf(
    "a", "b", "z", "A", "Z", "0", "9", " ", "|", "^", "\u0001", "\u0002",
    "\t", "\n", "é", "ß", "中", "😀", "\u00a0", ".", "-", "_", "!"
)

private fun randomString(rnd: Random): String {
    return when (rnd.nextInt(4)) {
        0 -> STR_POOL[rnd.nextInt(STR_POOL.size)]
        1 -> buildString {
            repeat(rnd.nextInt(0, 30)) { append(CH_POOL[rnd.nextInt(CH_POOL.size)]) }
        }
        2 -> {
            val n = listOf(
                0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 15, 16, 17, 19, 20, 21,
                23, 24, 25, 29, 30, 31, 39, 40, 41, 49, 50, 51, 59, 60, 61,
                79, 80, 81, 99, 100, 101, 119, 120, 121, 130, 159, 160, 161
            )[rnd.nextInt(45)]
            val ch = listOf("a", " ", "|", "😀")[rnd.nextInt(4)]
            ch.repeat(n)
        }
        else -> {
            val base = buildString {
                repeat(rnd.nextInt(0, 25)) { append('a' + rnd.nextInt(26)) }
            }
            base + listOf("", " ", "|", "^", "\u0001", "😀", "  ")[rnd.nextInt(7)]
        }
    }
}

private fun randomPostId(rnd: Random): String = listOf(
    "p1", "p2", "p3", "", "p", "P1", "p 1", "p|1", randomString(rnd)
)[rnd.nextInt(9)]

private fun randomUserId(rnd: Random): String = listOf(
    "u1", "u2", "u3", "", "u", "U1", randomString(rnd)
)[rnd.nextInt(7)]

private fun randomInt(rnd: Random): Int {
    return when (rnd.nextInt(5)) {
        0 -> listOf(
            0, 1, -1, 2, -2, 999, 1000, 9999, 10000, 10001,
            Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE - 1, Int.MIN_VALUE + 1
        )[rnd.nextInt(14)]
        1 -> rnd.nextInt(-10, 11)
        2 -> rnd.nextInt(0, 100000)
        3 -> rnd.nextInt()
        else -> rnd.nextInt(-1000, 1001)
    }
}

private fun randomLong(rnd: Random): Long {
    return when (rnd.nextInt(4)) {
        0 -> listOf(0L, 1L, -1L, 1000L, Long.MAX_VALUE, Long.MIN_VALUE)[rnd.nextInt(6)]
        // Small range forces createdAt ties, so oldestCursor exercises the id tiebreak.
        1 -> rnd.nextLong(0, 5)
        2 -> rnd.nextLong(0, 100000)
        else -> rnd.nextLong()
    }
}

private fun randomVisibility(rnd: Random): String {
    return when (rnd.nextInt(4)) {
        0 -> listOf(
            "PUBLIC", "CONTACTS", "PRIVATE", "FRIENDS", "public", "private",
            "contacts", "friends", "PUBLIC ", " PRIVATE", " CONTACTS ", "", " ",
            "UNKNOWN", "FRIEND", "PUBLICX", "PRIVAT", "  ", "\u00a0PUBLIC",
            "PUBLIC\u00a0", "pUbLiC", "CoNtAcTs"
        )[rnd.nextInt(21)]
        1 -> randomString(rnd)
        2 -> listOf("PUBLIC", "CONTACTS", "PRIVATE")[rnd.nextInt(3)]
        else -> randomString(rnd)
    }
}

private fun randomPost(rnd: Random): PostDto {
    return PostDto(
        id = randomPostId(rnd),
        author = UserDto(id = randomUserId(rnd), name = randomString(rnd)),
        content = randomString(rnd),
        visibility = randomVisibility(rnd),
        createdAt = randomLong(rnd),
        likeCount = randomInt(rnd),
        commentCount = randomInt(rnd),
        likedByMe = rnd.nextBoolean(),
        isMine = rnd.nextBoolean()
    )
}

private fun randomPostList(rnd: Random): List<PostDto> =
    List(rnd.nextInt(0, 6)) { randomPost(rnd) }

private fun randomTier(rnd: Random): PostVisibilityTier =
    PostVisibilityTier.entries[rnd.nextInt(PostVisibilityTier.entries.size)]

private fun randomToken(rnd: Random): String? {
    return when (rnd.nextInt(4)) {
        0 -> null
        1 -> listOf("", " ", "   ", "\t", "\n", "\u00a0", "tok", " token ")[rnd.nextInt(8)]
        2 -> randomString(rnd)
        else -> "token-" + rnd.nextInt(1000)
    }
}

private fun randomIdSet(rnd: Random): Set<String> {
    val pool = listOf("u1", "u2", "u3", "p1", "p2", "p3", "", "x")
    return List(rnd.nextInt(0, 4)) { pool[rnd.nextInt(pool.size)] }.toSet()
}

    @Test
    fun scopedKeyRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val base = randomString(rnd)
            val userId = randomString(rnd)
            assertEquals(scopedKeyOracle(base, userId), ExploreDraftPolicy.scopedKey(base, userId))
        }
    }

    @Test
    fun normalizeVisibilityRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val value = randomVisibility(rnd)
            val fallback = randomString(rnd)
            assertEquals(exploreDraftNormalizeVisibilityOracle(value), ExploreDraftPolicy.normalizeVisibility(value))
            assertEquals(normalizeVisibilityOracle(value, fallback), normalizeVisibility(value, fallback))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // scopedKey: a blank userId (NBSP counts as blank, NEL does not)
        // means "no key"; the userId is pasted verbatim, never trimmed.
        assertNull(ExploreDraftPolicy.scopedKey("composer_text", ""))
        assertNull(ExploreDraftPolicy.scopedKey("composer_text", "  "))
        assertNull(ExploreDraftPolicy.scopedKey("composer_text", "\t"))
        assertNull(ExploreDraftPolicy.scopedKey("k", "\u00a0"))
        assertEquals("k:\u0085", ExploreDraftPolicy.scopedKey("k", "\u0085"))
        assertEquals("composer_text:u1", ExploreDraftPolicy.scopedKey("composer_text", "u1"))
        assertEquals(":u1", ExploreDraftPolicy.scopedKey("", "u1"))
        assertEquals("composer_text: u1 ", ExploreDraftPolicy.scopedKey("composer_text", " u1 "))

        // normalizeVisibility is an exact, case-sensitive match: "public" and
        // "FRIENDS" are not recognised, so the draft falls back to PRIVATE
        // (the settings page would fall back to PUBLIC instead — G179).
        assertEquals("PUBLIC", ExploreDraftPolicy.normalizeVisibility("PUBLIC"))
        assertEquals("CONTACTS", ExploreDraftPolicy.normalizeVisibility("CONTACTS"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("PRIVATE"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("FRIENDS"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility(""))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("public"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility(" PRIVATE"))
        assertEquals("PUBLIC", normalizeVisibility("FRIENDS", "PUBLIC"))
        assertEquals("CUSTOM", normalizeVisibility("", "CUSTOM"))
        assertEquals("PUBLIC", normalizeVisibility("PUBLIC", "PRIVATE"))
    }
}
