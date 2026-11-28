package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fuzz / random-oracle tests for ExploreFeedPolicy (checklist line 931,
// 52nd block). Test-only, zero production changes. The oracle reimplements
// each function by hand; production is called directly, so any drift shows
// up as a mismatch. Pinned quirks: Int wrap before the zero clamp
// (toggleLike MAX -> 0, unlike MIN -> MAX), incrementCommentCount MAX -> MIN,
// formatCount 9999 -> "10K" / 10000 -> "1万", restorePost index clamping,
// NBSP counts as blank for isBlank() (Kotlin whitespace includes 0xA0).
class ExploreFeedPolicyFuzzTest {

    private fun post(
        id: String = "p1",
        authorId: String = "u1",
        likeCount: Int = 0,
        commentCount: Int = 0,
        likedByMe: Boolean = false,
        isMine: Boolean = false,
        visibility: String = "PUBLIC",
        createdAt: Long = 1L,
        content: String = "hello"
    ) = PostDto(
        id = id,
        author = UserDto(id = authorId, name = "Ada"),
        content = content,
        visibility = visibility,
        createdAt = createdAt,
        likeCount = likeCount,
        commentCount = commentCount,
        likedByMe = likedByMe,
        isMine = isMine
    )

// ---------- B1 oracles: ExploreFeedPolicy ----------

private fun toggleLikeOracle(post: PostDto): LikeToggle {
    // Liking adds one, unliking drops one; the count never goes below zero.
    val willLike = !post.likedByMe
    val raw = if (willLike) post.likeCount + 1 else post.likeCount - 1
    val nextCount = if (raw < 0) 0 else raw
    return LikeToggle(post.copy(likedByMe = willLike, likeCount = nextCount), post, willLike)
}

private fun applyServerPostOracle(posts: List<PostDto>, server: PostDto): List<PostDto> {
    val out = ArrayList<PostDto>(posts.size)
    for (p in posts) out.add(if (p.id == server.id) server else p)
    return out
}

private fun rollbackPostOracle(posts: List<PostDto>, previous: PostDto): List<PostDto> {
    val out = ArrayList<PostDto>(posts.size)
    for (p in posts) out.add(if (p.id == previous.id) previous else p)
    return out
}

private fun removePostOracle(posts: List<PostDto>, postId: String): List<PostDto> {
    val out = ArrayList<PostDto>(posts.size)
    for (p in posts) if (p.id != postId) out.add(p)
    return out
}

private fun restorePostOracle(posts: List<PostDto>, post: PostDto, originalIndex: Int): List<PostDto> {
    for (p in posts) if (p.id == post.id) return posts
    val index = originalIndex.coerceIn(0, posts.size)
    val out = posts.toMutableList()
    out.add(index, post)
    return out
}

private fun incrementCommentCountOracle(posts: List<PostDto>, postId: String): List<PostDto> {
    val out = ArrayList<PostDto>(posts.size)
    for (p in posts) {
        // Plain +1: Int.MAX_VALUE wraps to Int.MIN_VALUE, no clamping.
        out.add(if (p.id == postId) p.copy(commentCount = p.commentCount + 1) else p)
    }
    return out
}

private fun decrementCommentCountOracle(posts: List<PostDto>, postId: String): List<PostDto> {
    val out = ArrayList<PostDto>(posts.size)
    for (p in posts) {
        out.add(if (p.id == postId && p.commentCount > 0) p.copy(commentCount = p.commentCount - 1) else p)
    }
    return out
}

private fun clampImageIndexOracle(index: Int, size: Int): Int {
    if (size <= 0) return 0
    if (index < 0) return 0
    if (index > size - 1) return size - 1
    return index
}

private fun formatCountOracle(n: Int): String {
    if (n <= 0) return "0"
    if (n < 1000) return n.toString()
    val value = if (n < 10_000) n / 1000.0 else n / 10_000.0
    val suffix = if (n < 10_000) "K" else "万"
    val oneDecimal = String.format(java.util.Locale.US, "%.1f", value)
    val trimmed = if (oneDecimal.endsWith(".0")) oneDecimal.dropLast(2) else oneDecimal
    return trimmed + suffix
}

private fun canStartLoadMoreOracle(
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    postCount: Int
): Boolean = !isLoading && !isLoadingMore && hasMore && postCount > 0

private fun oldestCursorOracle(posts: List<PostDto>): Cursor? {
    var best: PostDto? = null
    for (p in posts) {
        val b = best
        if (b == null || p.createdAt < b.createdAt ||
            (p.createdAt == b.createdAt && p.id < b.id)
        ) best = p
    }
    return best?.let { Cursor(it.createdAt, it.id) }
}

private fun missingSessionForLoadMoreOracle(token: String?): Boolean = token.isNullOrBlank()

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
    fun toggleLikeRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val post = randomPost(rnd)
            val expected = toggleLikeOracle(post)
            val actual = ExploreFeedPolicy.toggleLike(post)
            assertEquals(expected.willLike, actual.willLike)
            assertEquals(expected.optimistic, actual.optimistic)
            assertEquals(expected.previous, actual.previous)
        }
    }

    @Test
    fun applyServerAndRollbackRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val posts = randomPostList(rnd)
            val server = randomPost(rnd)
            val previous = randomPost(rnd)
            assertEquals(applyServerPostOracle(posts, server), ExploreFeedPolicy.applyServerPost(posts, server))
            assertEquals(rollbackPostOracle(posts, previous), ExploreFeedPolicy.rollbackPost(posts, previous))
        }
    }

    @Test
    fun removeAndRestoreRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val posts = randomPostList(rnd)
            val postId = randomPostId(rnd)
            val post = randomPost(rnd)
            val index = randomInt(rnd)
            assertEquals(removePostOracle(posts, postId), ExploreFeedPolicy.removePost(posts, postId))
            assertEquals(restorePostOracle(posts, post, index), ExploreFeedPolicy.restorePost(posts, post, index))
        }
    }

    @Test
    fun commentCountRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val posts = randomPostList(rnd)
            val postId = randomPostId(rnd)
            assertEquals(incrementCommentCountOracle(posts, postId), ExploreFeedPolicy.incrementCommentCount(posts, postId))
            assertEquals(decrementCommentCountOracle(posts, postId), ExploreFeedPolicy.decrementCommentCount(posts, postId))
        }
    }

    @Test
    fun clampImageIndexRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val index = randomInt(rnd)
            val size = randomInt(rnd)
            assertEquals(clampImageIndexOracle(index, size), ExploreFeedPolicy.clampImageIndex(index, size))
        }
    }

    @Test
    fun formatCountRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val n = randomInt(rnd)
            assertEquals(formatCountOracle(n), ExploreFeedPolicy.formatCount(n))
        }
    }

    @Test
    fun loadMoreGuardsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val isLoading = rnd.nextBoolean()
            val isLoadingMore = rnd.nextBoolean()
            val hasMore = rnd.nextBoolean()
            val postCount = randomInt(rnd)
            val token = randomToken(rnd)
            assertEquals(
                canStartLoadMoreOracle(isLoading, isLoadingMore, hasMore, postCount),
                ExploreFeedPolicy.canStartLoadMore(isLoading, isLoadingMore, hasMore, postCount)
            )
            assertEquals(
                missingSessionForLoadMoreOracle(token),
                ExploreFeedPolicy.missingSessionForLoadMore(token)
            )
        }
    }

    @Test
    fun oldestCursorRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val posts = randomPostList(rnd)
            assertEquals(oldestCursorOracle(posts), ExploreFeedPolicy.oldestCursor(posts))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // toggleLike wraps at the Int bounds before the zero clamp.
        val likedMax = ExploreFeedPolicy.toggleLike(post(likedByMe = false, likeCount = Int.MAX_VALUE))
        assertTrue(likedMax.willLike)
        assertEquals(0, likedMax.optimistic.likeCount)
        assertEquals(Int.MAX_VALUE, likedMax.previous.likeCount)
        val unlikedMin = ExploreFeedPolicy.toggleLike(post(likedByMe = true, likeCount = Int.MIN_VALUE))
        assertFalse(unlikedMin.willLike)
        assertEquals(Int.MAX_VALUE, unlikedMin.optimistic.likeCount)
        val unlikedZero = ExploreFeedPolicy.toggleLike(post(likedByMe = true, likeCount = 0))
        assertEquals(0, unlikedZero.optimistic.likeCount)
        assertFalse(unlikedZero.optimistic.likedByMe)
        val likedNegative = ExploreFeedPolicy.toggleLike(post(likedByMe = false, likeCount = -5))
        assertEquals(0, likedNegative.optimistic.likeCount)

        // Comment counts: increment wraps silently, decrement stops at zero.
        val maxed = listOf(post(commentCount = Int.MAX_VALUE))
        assertEquals(Int.MIN_VALUE, ExploreFeedPolicy.incrementCommentCount(maxed, "p1")[0].commentCount)
        assertEquals(0, ExploreFeedPolicy.decrementCommentCount(listOf(post(commentCount = 0)), "p1")[0].commentCount)
        assertEquals(0, ExploreFeedPolicy.decrementCommentCount(listOf(post(commentCount = 1)), "p1")[0].commentCount)
        assertEquals(Int.MIN_VALUE, ExploreFeedPolicy.decrementCommentCount(listOf(post(commentCount = Int.MIN_VALUE)), "p1")[0].commentCount)

        // formatCount boundaries, including the .0 trim and the 万 switch.
        val counts = listOf(
            0 to "0", -3 to "0", 999 to "999", 1000 to "1K", 1250 to "1.3K",
            9999 to "10K", 10000 to "1万", 10500 to "1.1万", 99999 to "10万",
            100000 to "10万", 1000000 to "100万", Int.MAX_VALUE to "214748.4万"
        )
        for ((n, want) in counts) assertEquals(want, ExploreFeedPolicy.formatCount(n), "formatCount($n)")

        // clampImageIndex: empty gallery always 0, otherwise into [0, size-1].
        val clamps = listOf(
            Triple(5, 0, 0), Triple(5, -3, 0), Triple(-1, 3, 0),
            Triple(0, 3, 0), Triple(2, 3, 2), Triple(3, 3, 2), Triple(99, 3, 2)
        )
        for ((index, size, want) in clamps) {
            assertEquals(want, ExploreFeedPolicy.clampImageIndex(index, size), "clamp($index, $size)")
        }

        // restorePost: existing id is a no-op; out-of-range index clamps.
        val two = listOf(post(id = "p1"), post(id = "p2"))
        assertEquals(two, ExploreFeedPolicy.restorePost(two, post(id = "p1"), 0))
        assertEquals(post(id = "p3"), ExploreFeedPolicy.restorePost(two, post(id = "p3"), -5)[0])
        assertEquals(post(id = "p3"), ExploreFeedPolicy.restorePost(two, post(id = "p3"), 99)[2])
        assertEquals(listOf(post(id = "p3")), ExploreFeedPolicy.restorePost(emptyList(), post(id = "p3"), 5))
        assertEquals(two, ExploreFeedPolicy.removePost(two, "missing"))
        assertEquals(listOf(post(id = "p2")), ExploreFeedPolicy.removePost(two, "p1"))
        val replaced = ExploreFeedPolicy.applyServerPost(two, post(id = "p2", content = "new"))
        assertEquals("new", replaced[1].content)
        assertEquals("hello", replaced[0].content)

        // oldestCursor: empty is null; ties on createdAt break on the smaller id.
        assertNull(ExploreFeedPolicy.oldestCursor(emptyList()))
        val tied = listOf(post(id = "p2", createdAt = 5L), post(id = "p1", createdAt = 5L), post(id = "p0", createdAt = 9L))
        assertEquals(Cursor(createdAt = 5L, postId = "p1"), ExploreFeedPolicy.oldestCursor(tied))

        // Load-more guards: every flag must be in the right state, and the
        // feed must be non-empty; a blank token means "no session".
        assertTrue(ExploreFeedPolicy.canStartLoadMore(false, false, true, 1))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(false, false, true, 0))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(true, false, true, 1))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(false, true, true, 1))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(false, false, false, 1))
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore(null))
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore(""))
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore("   "))
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore("\t"))
        // NBSP counts as blank for isBlank(), so it still means "no session".
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore("\u00a0"))
        assertFalse(ExploreFeedPolicy.missingSessionForLoadMore("tok"))
    }
}
