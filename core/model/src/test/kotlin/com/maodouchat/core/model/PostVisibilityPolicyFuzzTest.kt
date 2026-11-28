package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fuzz / random-oracle tests for PostVisibilityPolicy + PostVisibilityTier
// (checklist line 931, 52nd block). Test-only, zero production changes.
// Pinned quirks: unknown visibility fails closed to PRIVATE, FRIENDS folds
// into CONTACTS, a blank viewer is never the author even when isMine is true,
// blocks/reports beat authorship.
class PostVisibilityPolicyFuzzTest {

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

// ---------- B2 oracles: PostVisibility ----------

private fun fromStringOracle(value: String?): PostVisibilityTier = when (value?.trim()?.uppercase()) {
    "PUBLIC" -> PostVisibilityTier.PUBLIC
    "CONTACTS", "FRIENDS" -> PostVisibilityTier.CONTACTS
    "PRIVATE" -> PostVisibilityTier.PRIVATE
    // Fail-closed: anything unrecognised (including null/blank) is private.
    else -> PostVisibilityTier.PRIVATE
}

private fun formatForApiOracle(tier: PostVisibilityTier): String = tier.name

private fun isVisibleOracle(
    post: PostDto,
    viewerUserId: String,
    isFriend: Boolean = false,
    isAuthorBlocked: Boolean = false,
    isReported: Boolean = false
): Boolean {
    if (isAuthorBlocked || isReported) return false
    // The author check needs a non-blank viewer: a blank viewer is never
    // treated as the author, even when post.isMine is true.
    val isAuthor = viewerUserId.isNotBlank() && (post.author.id == viewerUserId || post.isMine)
    if (isAuthor) return true
    return when (fromStringOracle(post.visibility)) {
        PostVisibilityTier.PUBLIC -> true
        PostVisibilityTier.CONTACTS -> isFriend
        PostVisibilityTier.PRIVATE -> false
    }
}

private fun filterVisiblePostsOracle(
    posts: List<PostDto>,
    viewerUserId: String,
    friendUserIds: Set<String> = emptySet(),
    blockedUserIds: Set<String> = emptySet(),
    reportedPostIds: Set<String> = emptySet()
): List<PostDto> {
    if (posts.isEmpty()) return emptyList()
    return posts.filter { post ->
        isVisibleOracle(
            post = post,
            viewerUserId = viewerUserId,
            isFriend = friendUserIds.contains(post.author.id),
            isAuthorBlocked = blockedUserIds.contains(post.author.id),
            isReported = reportedPostIds.contains(post.id)
        )
    }
}

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
    fun tierRoundTripRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val tier = randomTier(rnd)
            val value = if (rnd.nextInt(6) == 0) null else randomVisibility(rnd)
            assertEquals(formatForApiOracle(tier), PostVisibilityPolicy.formatForApi(tier))
            assertEquals(fromStringOracle(value), PostVisibilityTier.fromString(value))
        }
    }

    @Test
    fun isVisibleRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val post = randomPost(rnd)
            val viewer = randomUserId(rnd)
            val isFriend = rnd.nextBoolean()
            val isBlocked = rnd.nextBoolean()
            val isReported = rnd.nextBoolean()
            assertEquals(
                isVisibleOracle(post, viewer, isFriend, isBlocked, isReported),
                PostVisibilityPolicy.isVisible(post, viewer, isFriend, isBlocked, isReported)
            )
        }
    }

    @Test
    fun filterVisiblePostsRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val posts = randomPostList(rnd)
            val viewer = randomUserId(rnd)
            val friends = randomIdSet(rnd)
            val blocked = randomIdSet(rnd)
            val reported = randomIdSet(rnd)
            assertEquals(
                filterVisiblePostsOracle(posts, viewer, friends, blocked, reported),
                PostVisibilityPolicy.filterVisiblePosts(posts, viewer, friends, blocked, reported)
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // fromString: FRIENDS folds into CONTACTS, anything unknown fails closed.
        val tiers = listOf(
            null to PostVisibilityTier.PRIVATE,
            "" to PostVisibilityTier.PRIVATE,
            "   " to PostVisibilityTier.PRIVATE,
            "PUBLIC" to PostVisibilityTier.PUBLIC,
            " public " to PostVisibilityTier.PUBLIC,
            "FRIENDS" to PostVisibilityTier.CONTACTS,
            "friends" to PostVisibilityTier.CONTACTS,
            "CONTACTS" to PostVisibilityTier.CONTACTS,
            "PRIVATE" to PostVisibilityTier.PRIVATE,
            "UNKNOWN" to PostVisibilityTier.PRIVATE,
            "FRIEND" to PostVisibilityTier.PRIVATE,
            "PUBLICX" to PostVisibilityTier.PRIVATE,
            // trim() strips NBSP (Kotlin whitespace includes 0xA0) but not
            // NEL (0x85 is not in Kotlin's whitespace set).
            "\u00a0PUBLIC" to PostVisibilityTier.PUBLIC,
            "\u0085PUBLIC" to PostVisibilityTier.PRIVATE
        )
        for ((value, want) in tiers) assertEquals(want, PostVisibilityTier.fromString(value), "fromString($value)")
        assertEquals("PUBLIC", PostVisibilityPolicy.formatForApi(PostVisibilityTier.PUBLIC))
        assertEquals("CONTACTS", PostVisibilityPolicy.formatForApi(PostVisibilityTier.CONTACTS))
        assertEquals("PRIVATE", PostVisibilityPolicy.formatForApi(PostVisibilityTier.PRIVATE))

        // isVisible: blocks/reports beat authorship; a blank viewer is never
        // the author, even when the post is marked mine.
        val mine = post(isMine = true, visibility = "PRIVATE")
        assertTrue(PostVisibilityPolicy.isVisible(mine, "u2"))
        assertFalse(PostVisibilityPolicy.isVisible(mine, ""))
        assertFalse(PostVisibilityPolicy.isVisible(mine, "u2", isAuthorBlocked = true))
        assertFalse(PostVisibilityPolicy.isVisible(mine, "u2", isReported = true))
        assertTrue(PostVisibilityPolicy.isVisible(post(authorId = "u1", visibility = "PUBLIC"), "u2"))
        assertFalse(PostVisibilityPolicy.isVisible(post(authorId = "u1", visibility = "PRIVATE"), "u2"))
        assertTrue(PostVisibilityPolicy.isVisible(post(authorId = "u1", visibility = "CONTACTS"), "u2", isFriend = true))
        assertFalse(PostVisibilityPolicy.isVisible(post(authorId = "u1", visibility = "CONTACTS"), "u2", isFriend = false))
        assertFalse(PostVisibilityPolicy.isVisible(post(authorId = "u1", visibility = "FRIENDS"), "u2", isFriend = false))
        assertTrue(PostVisibilityPolicy.isVisible(post(authorId = "u1", visibility = "FRIENDS"), "u2", isFriend = true))

        // filterVisiblePosts: each gate (friend/blocked/reported) applies per post.
        val feed = listOf(
            post(id = "p1", authorId = "u1", visibility = "PUBLIC"),
            post(id = "p2", authorId = "u2", visibility = "CONTACTS"),
            post(id = "p3", authorId = "u3", visibility = "PUBLIC"),
            post(id = "p4", authorId = "u1", visibility = "PUBLIC")
        )
        val visible = PostVisibilityPolicy.filterVisiblePosts(
            feed, "viewer", setOf("u2"), setOf("u3"), setOf("p4")
        )
        assertEquals(listOf("p1", "p2"), visible.map { it.id })
        assertEquals(emptyList<PostDto>(), PostVisibilityPolicy.filterVisiblePosts(emptyList(), "viewer"))
    }
}
