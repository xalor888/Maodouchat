package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// replace restated by hand: every post whose id matches the target
// is swapped for the updated post; all other rows keep their place
// and their content, duplicates included.
internal fun replacePostOracle171(
    posts: List<PostDto>,
    postId: String,
    updated: PostDto
): List<PostDto> =
    posts.map { if (it.id == postId) updated else it }

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJson171(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

// Production replace returns the list with matches swapped.
internal fun replacePostProduction171(
    posts: List<PostDto>,
    postId: String,
    updated: PostDto
): List<PostDto> =
    ExplorePostReplacePolicy.replace(posts, postId, updated)

// ---------- generators ----------

// Posts are built with a fixed author and timestamp so equality stays
// deterministic; the body marks the position (body0, body1, ...)
// so a result list shows exactly which rows were swapped.
internal fun post171(id: String, content: String): PostDto =
    PostDto(id = id, author = UserDto("u1", "Alice"), content = content, createdAt = 0L)

internal fun posts171(ids: List<String>): List<PostDto> =
    ids.mapIndexed { index, id -> post171(id, "body$index") }

internal data class ReplacePostCase171(
    val ids: List<String>,
    val target: String,
    val updatedId: String,
    val updatedContent: String
)

internal val REPLACE_POST_ID_POOL171: List<String> = listOf(
    "", "p1", "p2", "p3", " ", "p1 ", "P1"
)

internal val REPLACE_POST_CONTENT_POOL171: List<String> = listOf(
    "", "hello", "new", "x".repeat(30), "a b", "  "
)

internal fun randomReplacePostCase171(rnd: Random): ReplacePostCase171 {
    val n = rnd.nextInt(6)
    val ids = List(n) { REPLACE_POST_ID_POOL171[rnd.nextInt(REPLACE_POST_ID_POOL171.size)] }
    return ReplacePostCase171(
        ids = ids,
        target = REPLACE_POST_ID_POOL171[rnd.nextInt(REPLACE_POST_ID_POOL171.size)],
        updatedId = REPLACE_POST_ID_POOL171[rnd.nextInt(REPLACE_POST_ID_POOL171.size)],
        updatedContent = REPLACE_POST_CONTENT_POOL171[rnd.nextInt(REPLACE_POST_CONTENT_POOL171.size)]
    )
}

internal fun replacePostWireJson171(c: ReplacePostCase171): String {
    val ids = c.ids.joinToString(",") { escJson171(it) }
    return "{\"ids\":[$ids],\"target\":${escJson171(c.target)}," +
        "\"updatedId\":${escJson171(c.updatedId)}," +
        "\"updatedContent\":${escJson171(c.updatedContent)}}"
}

class ExplorePostReplacePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val upd = post171("p9", "new")
        val cases = listOf(
            Triple(emptyList<String>(), "p1", upd),
            Triple(listOf("p1", "p2"), "p3", upd),
            Triple(listOf("p1", "p2"), "p1", upd),
            Triple(listOf("p1", "p1", "p2"), "p1", upd),
            Triple(listOf("", "p1"), "", upd),
            Triple(listOf("p1"), "p1", post171("p1", "fresh")),
            Triple(listOf("p1"), "P1", upd)
        )
        for ((ids, target, updated) in cases) {
            val expected = ids.mapIndexed { index, id ->
                if (id == target) updated else post171(id, "body$index")
            }
            assertEquals(expected, replacePostProduction171(posts171(ids), target, updated))
            assertEquals(expected, replacePostOracle171(posts171(ids), target, updated))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(171003)
        repeat(4000) {
            val c = randomReplacePostCase171(rnd)
            val updated = post171(c.updatedId, c.updatedContent)
            assertEquals(
                replacePostOracle171(posts171(c.ids), c.target, updated),
                replacePostProduction171(posts171(c.ids), c.target, updated),
                "replace(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        val upd = post171("p9", "new")
        // Every target against the full id pool, and every list length.
        val ids = listOf("p1", "p1", "p2", "p3", "", " ", "P1")
        for (target in REPLACE_POST_ID_POOL171) {
            assertEquals(
                replacePostOracle171(posts171(ids), target, upd),
                replacePostProduction171(posts171(ids), target, upd),
                "sweep-target($target)"
            )
        }
        for (n in 0..6) {
            val listIds = List(n) { REPLACE_POST_ID_POOL171[it % REPLACE_POST_ID_POOL171.size] }
            for (target in listOf("p1", "p2", "", "zzz")) {
                assertEquals(
                    replacePostOracle171(posts171(listIds), target, upd),
                    replacePostProduction171(posts171(listIds), target, upd),
                    "sweep-len(n=$n,target=$target)"
                )
            }
        }
        // Every updated id against a two-row list.
        for (updatedId in REPLACE_POST_ID_POOL171) {
            val updated = post171(updatedId, "new")
            assertEquals(
                replacePostOracle171(posts171(listOf("p1", "p2")), "p1", updated),
                replacePostProduction171(posts171(listOf("p1", "p2")), "p1", updated),
                "sweep-updated($updatedId)"
            )
        }
    }
}
