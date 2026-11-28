// Fuzz / random-oracle tests for ExploreCommentReplacePolicy
// (checklist line 931, 160th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the list is
// mapped position by position, every comment whose id equals the target
// id is replaced wholesale by the updated comment, everything else is
// kept as-is, order included. Only the id field decides; postId,
// content, author and the counters play no part. Equality is exact
// (case-sensitive).
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostCommentDto
import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun commentReplaceOracle160(
    comments: List<PostCommentDto>,
    commentId: String,
    updated: PostCommentDto,
): List<PostCommentDto> =
    comments.map { if (it.id == commentId) updated else it }

// ---------- generators ----------

internal val COMMENT_ID_POOL160 = listOf(
    "c1", "c2", "C1", "c1 ", " c1", "", " ", "x", "m1", "u1", "p1",
    "\u00df", "\u1e9e", "\u0130", "\u0131", "\ufb00", "FF", "c1\u00a0",
    "c1\u0085", "c1\u2003", "cc", "c11", "1", "0",
)

internal val COMMENT_ALPHABET160 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003c12mxuC\u0130\u0131I i\u00df\u1e9eK\u212a k\u017fsS\u00b5"

internal fun randomCommentChar160(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> COMMENT_ALPHABET160[rnd.nextInt(COMMENT_ALPHABET160.length)].toString()
}

internal fun randomCommentString160(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomCommentChar160(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal fun comment160(id: String, content: String, index: Int): PostCommentDto =
    PostCommentDto(
        id = id,
        postId = "p1",
        author = UserDto(id = "u1", name = "A"),
        content = content,
        createdAt = index.toLong(),
    )

internal fun randomComment160(rnd: Random, index: Int): PostCommentDto {
    val id = if (rnd.nextInt(10) < 6) COMMENT_ID_POOL160[rnd.nextInt(COMMENT_ID_POOL160.size)] else randomCommentString160(rnd)
    return PostCommentDto(
        id = id,
        postId = if (rnd.nextInt(10) < 7) "p1" else randomCommentString160(rnd),
        author = UserDto(id = "u$index", name = randomCommentString160(rnd)),
        content = randomCommentString160(rnd),
        createdAt = rnd.nextLong(),
        isMine = rnd.nextBoolean(),
        parentId = if (rnd.nextInt(10) < 7) null else randomCommentString160(rnd),
        likeCount = rnd.nextInt(),
        likedByMe = rnd.nextBoolean(),
    )
}

internal data class CommentReplaceCase160(
    val comments: List<PostCommentDto>,
    val commentId: String,
    val updated: PostCommentDto,
)

internal fun randomCommentReplaceCase160(rnd: Random): CommentReplaceCase160 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    val comments = List(count) { i -> randomComment160(rnd, i) }
    val commentId = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> if (comments.isNotEmpty()) comments[rnd.nextInt(comments.size)].id else "c1"
        2 -> COMMENT_ID_POOL160[rnd.nextInt(COMMENT_ID_POOL160.size)]
        else -> randomCommentString160(rnd)
    }
    return CommentReplaceCase160(comments, commentId, randomComment160(rnd, 99))
}

internal fun commentJsonEscape160(s: String): String {
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

internal fun commentJson160(c: PostCommentDto): String {
    val sb = StringBuilder()
    sb.append("{\"id\":\"").append(commentJsonEscape160(c.id)).append("\"")
    sb.append(",\"postId\":\"").append(commentJsonEscape160(c.postId)).append("\"")
    sb.append(",\"authorId\":\"").append(commentJsonEscape160(c.author.id)).append("\"")
    sb.append(",\"authorName\":\"").append(commentJsonEscape160(c.author.name)).append("\"")
    sb.append(",\"content\":\"").append(commentJsonEscape160(c.content)).append("\"")
    sb.append(",\"createdAt\":").append(c.createdAt)
    sb.append(",\"isMine\":").append(if (c.isMine) "true" else "false")
    val parentId = c.parentId
    if (parentId != null) {
        sb.append(",\"parentId\":\"").append(commentJsonEscape160(parentId)).append("\"")
    }
    sb.append(",\"likeCount\":").append(c.likeCount)
    sb.append(",\"likedByMe\":").append(if (c.likedByMe) "true" else "false")
    sb.append("}")
    return sb.toString()
}

internal fun commentReplaceWireJson160(case: CommentReplaceCase160): String {
    val sb = StringBuilder()
    sb.append("{\"comments\":[")
    sb.append(case.comments.joinToString(",") { commentJson160(it) })
    sb.append("],\"commentId\":\"").append(commentJsonEscape160(case.commentId)).append("\"")
    sb.append(",\"updated\":").append(commentJson160(case.updated))
    sb.append("}")
    return sb.toString()
}

class ExploreCommentReplacePolicyFuzzTest {

    private fun check(comments: List<PostCommentDto>, commentId: String, updated: PostCommentDto) {
        assertEquals(
            commentReplaceOracle160(comments, commentId, updated),
            ExploreCommentReplacePolicy.replace(comments, commentId, updated),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Middle / first / last replacement keeps order and the rest.
        val three = listOf(comment160("c1", "a", 0), comment160("c2", "b", 1), comment160("c3", "c", 2))
        assertEquals(
            listOf(comment160("c1", "a", 0), comment160("c2", "B", 1), comment160("c3", "c", 2)),
            ExploreCommentReplacePolicy.replace(three, "c2", comment160("c2", "B", 1)),
        )
        assertEquals(
            listOf(comment160("c1", "A", 0), comment160("c2", "b", 1), comment160("c3", "c", 2)),
            ExploreCommentReplacePolicy.replace(three, "c1", comment160("c1", "A", 0)),
        )
        assertEquals(
            listOf(comment160("c1", "a", 0), comment160("c2", "b", 1), comment160("c3", "C", 2)),
            ExploreCommentReplacePolicy.replace(three, "c3", comment160("c3", "C", 2)),
        )
        // No match: the list comes back untouched.
        assertEquals(three, ExploreCommentReplacePolicy.replace(three, "zzz", comment160("zzz", "Z", 9)))
        // Empty list stays empty.
        assertEquals(emptyList(), ExploreCommentReplacePolicy.replace(emptyList(), "c1", comment160("c1", "A", 0)))
        // A duplicated id is replaced at every position (map, not find-first).
        val dup = listOf(comment160("c1", "a", 0), comment160("c2", "b", 1), comment160("c1", "c", 2))
        assertEquals(
            listOf(comment160("c1", "X", 9), comment160("c2", "b", 1), comment160("c1", "X", 9)),
            ExploreCommentReplacePolicy.replace(dup, "c1", comment160("c1", "X", 9)),
        )
        // The updated object wins wholesale: its own id need not equal the target.
        assertEquals(
            listOf(comment160("c1", "a", 0), comment160("other", "B", 1), comment160("c3", "c", 2)),
            ExploreCommentReplacePolicy.replace(three, "c2", comment160("other", "B", 1)),
        )
        // Id equality is exact and case-sensitive.
        assertEquals(three, ExploreCommentReplacePolicy.replace(three, "C2", comment160("C2", "B", 1)))
        assertEquals(three, ExploreCommentReplacePolicy.replace(three, "c2 ", comment160("c2 ", "B", 1)))
        assertEquals(three, ExploreCommentReplacePolicy.replace(three, " c2", comment160(" c2", "B", 1)))
        for (id in COMMENT_ID_POOL160) {
            check(listOf(comment160(id, "a", 0)), id, comment160(id, "A", 0))
            check(three, id, comment160(id, "X", 9))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(160002)
        repeat(2000) {
            val case = randomCommentReplaceCase160(rnd)
            check(case.comments, case.commentId, case.updated)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pair of pool ids in a 2-comment list, every pool target.
        for (id1 in COMMENT_ID_POOL160) {
            for (id2 in COMMENT_ID_POOL160) {
                val two = listOf(comment160(id1, "a", 0), comment160(id2, "b", 1))
                for (target in COMMENT_ID_POOL160) {
                    check(two, target, comment160(target, "X", 9))
                }
            }
        }
        // List lengths 0..20 of the same id, target hit and miss.
        for (n in 0..20) {
            val same = List(n) { i -> comment160("c1", "v$i", i) }
            check(same, "c1", comment160("c1", "X", 99))
            check(same, "c2", comment160("c2", "X", 99))
            check(same, "", comment160("", "X", 99))
        }
        // Every single alphabet char as the id.
        for (c in COMMENT_ALPHABET160) {
            check(listOf(comment160(c.toString(), "a", 0)), c.toString(), comment160(c.toString(), "A", 0))
            check(listOf(comment160(c.toString(), "a", 0)), "x", comment160("x", "A", 0))
        }
        // "c1" with every alphabet char as a prefix or a suffix.
        for (c in COMMENT_ALPHABET160) {
            check(listOf(comment160(c + "c1", "a", 0)), "c1", comment160("c1", "A", 0))
            check(listOf(comment160("c1" + c, "a", 0)), "c1", comment160("c1", "A", 0))
            check(listOf(comment160("c1", "a", 0)), c + "c1", comment160(c + "c1", "A", 0))
        }
    }
}
