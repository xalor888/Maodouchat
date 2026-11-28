// Fuzz / random-oracle tests for ExplorePostLookupPolicy.findById
// (checklist line 931, 139th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the first post
// whose id equals the target wins, else null. Only the id is load-bearing
// -- author / content / timestamps are fixed dummies -- so the oracle
// compares ids only. Duplicate ids are unobservable through the result
// (both matches carry the same id), so first-wins is pinned by the
// differential's contains-mutant positive control instead of here.
// No fuzz test covered this function before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files for ExplorePostLookupPolicy / findById).
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun findByIdOracle(posts: List<PostDto>, postId: String): String? =
    posts.firstOrNull { it.id == postId }?.id

private fun post(id: String): PostDto =
    PostDto(id = id, author = UserDto(id = "a", name = "A"), content = "", createdAt = 0)

// ---------- generators (mirrored by Oracle139 GEN, seed 139001) ----------

private val EPL_ID_POOL = arrayOf(
    "", " ", "p1", "p2", "post-1", "12345678", "123456789", "a b",
    "a\uD800b", "\uD83D\uDE00", "system", "x"
)

private fun genEplId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) EPL_ID_POOL[rnd.nextInt(EPL_ID_POOL.size)]
    else randomText(rnd, 10)

private data class EplCase(
    val ids: List<String>,
    val target: String,
)

private fun genEplCase(rnd: Random): EplCase {
    val nPosts = rnd.nextInt(0, 5)
    val ids = List(nPosts) { genEplId(rnd) }
    val target = if (ids.isNotEmpty() && rnd.nextInt(3) == 0) {
        ids[rnd.nextInt(ids.size)]
    } else {
        genEplId(rnd)
    }
    return EplCase(ids, target)
}

class ExplorePostLookupPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Empty list never matches.
        assertNull(ExplorePostLookupPolicy.findById(emptyList(), "p1"))
        // A single exact match is found.
        assertEquals("p1", ExplorePostLookupPolicy.findById(listOf(post("p1")), "p1")?.id)
        // No exact match: null, even for a prefix or a suffix of an id.
        assertNull(ExplorePostLookupPolicy.findById(listOf(post("p1")), "p"))
        assertNull(ExplorePostLookupPolicy.findById(listOf(post("p1")), "p1x"))
        assertNull(ExplorePostLookupPolicy.findById(listOf(post("p1")), "P1"))
        // The match can sit anywhere in the list.
        val posts = listOf(post("a"), post("b"), post("c"))
        assertEquals("c", ExplorePostLookupPolicy.findById(posts, "c")?.id)
        assertEquals("a", ExplorePostLookupPolicy.findById(posts, "a")?.id)
        // Empty-string ids match exactly too.
        assertEquals("", ExplorePostLookupPolicy.findById(listOf(post("")), "")?.id)
        assertNull(ExplorePostLookupPolicy.findById(listOf(post("")), " "))
        // Duplicate ids return that id (order is unobservable here).
        assertEquals(
            "p1",
            ExplorePostLookupPolicy.findById(listOf(post("p1"), post("p1")), "p1")?.id
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle139.genEpl consumption order exactly, so seed
        // 139001 replays the same 1500 GEN lines drive139.py ran.
        val rnd = Random(139001)
        repeat(1500) {
            val c = genEplCase(rnd)
            val posts = c.ids.map(::post)
            assertEquals(
                findByIdOracle(posts, c.target),
                ExplorePostLookupPolicy.findById(posts, c.target)?.id
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Target pool x list shapes.
        val shapes: List<(String) -> List<PostDto>> = listOf(
            { emptyList() },
            { t -> listOf(post(t)) },
            { t -> listOf(post("other")) },
            { t -> listOf(post("other"), post(t)) },
            { t -> listOf(post(t), post("other")) },
            { t -> listOf(post(t), post(t)) },
            { t -> listOf(post("o1"), post("o2")) },
        )
        for (target in EPL_ID_POOL) {
            for (shape in shapes) {
                val posts = shape(target)
                assertEquals(
                    findByIdOracle(posts, target),
                    ExplorePostLookupPolicy.findById(posts, target)?.id
                )
            }
        }
        // Id lengths 0..12, exact target and off-by-one target.
        for (n in 0..12) {
            val id = "a".repeat(n)
            val posts = listOf(post(id))
            assertEquals(
                findByIdOracle(posts, id),
                ExplorePostLookupPolicy.findById(posts, id)?.id
            )
            assertEquals(
                findByIdOracle(posts, id + "b"),
                ExplorePostLookupPolicy.findById(posts, id + "b")?.id
            )
        }
        // Every single low unit as a post id (target matches) and as a
        // target (post id is "p1", so it only matches the "p" unit).
        for (code in 0x00..0x7F) {
            val ch = code.toChar().toString()
            val byId = listOf(post(ch))
            assertEquals(
                findByIdOracle(byId, ch),
                ExplorePostLookupPolicy.findById(byId, ch)?.id
            )
            val byTarget = listOf(post("p1"))
            assertEquals(
                findByIdOracle(byTarget, ch),
                ExplorePostLookupPolicy.findById(byTarget, ch)?.id
            )
        }
    }
}
