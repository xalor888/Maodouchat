package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ExploreFeedFilterPolicy] (checklist line 931, 43rd block).
 * Test-only, zero production changes.
 *
 * The oracle is a single combined predicate per entry point, while production filters
 * in stages (mine, then media, then search; moments first take the 30-day public
 * window), so a wrong pipeline order shows up as a mismatch. The search step reuses
 * the same code-point case-insensitive oracle as ExploreSearchPolicyFuzzTest, and the
 * Python mini-model differentially checks this oracle against the real JDK on 5000
 * random inputs (0 diffs).
 *
 * Boundary rules pinned here: publicRecent keeps createdAt == nowMs - THIRTY_DAYS_MS
 * (>=, inclusive) and visibility is a case-sensitive == "PUBLIC".
 */
class ExploreFeedFilterPolicyFuzzTest {

    private fun compareCodePointIgnoreCase(c1: Int, c2: Int): Boolean {
        if (c1 == c2) return true
        val u1 = Character.toUpperCase(c1)
        val u2 = Character.toUpperCase(c2)
        if (u1 == u2) return true
        return Character.toLowerCase(u1) == Character.toLowerCase(u2)
    }

    private fun containsIgnoreCase(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        val needleCps = needle.codePointCount(0, needle.length)
        val hayCps = haystack.codePointCount(0, haystack.length)
        if (needleCps > hayCps) return false
        for (startCp in 0..hayCps - needleCps) {
            var hIdx = haystack.offsetByCodePoints(0, startCp)
            var nIdx = 0
            var ok = true
            while (nIdx < needle.length) {
                val hc = haystack.codePointAt(hIdx)
                val nc = needle.codePointAt(nIdx)
                if (!compareCodePointIgnoreCase(hc, nc)) {
                    ok = false
                    break
                }
                hIdx += Character.charCount(hc)
                nIdx += Character.charCount(nc)
            }
            if (ok) return true
        }
        return false
    }

    private fun isKotlinWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000' ||
            c == '\u00A0' || c == '\u2007' || c == '\u202F'

    private fun kotlinTrim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isKotlinWhitespace(s[a])) a++
        while (b > a && isKotlinWhitespace(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun kotlinBlank(s: String): Boolean =
        s.isEmpty() || s.all { isKotlinWhitespace(it) }

    private fun oracleMatchesSearch(post: PostDto, query: String): Boolean =
        containsIgnoreCase(post.content, query) ||
            containsIgnoreCase(post.author.name, query) ||
            containsIgnoreCase(post.author.id, query)

    private fun oracleIsMine(post: PostDto, currentUserId: String): Boolean =
        post.isMine || post.author.id == currentUserId

    private fun oracleHasMedia(post: PostDto): Boolean = post.imageUrls.isNotEmpty()

    private fun oraclePublicRecent(posts: List<PostDto>, nowMs: Long): List<PostDto> =
        posts.filter { it.visibility == "PUBLIC" && it.createdAt >= nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS }

    private fun oracleFilterFeed(
        posts: List<PostDto>,
        currentUserId: String,
        showOnlyMine: Boolean,
        showOnlyMedia: Boolean,
        query: String
    ): List<PostDto> {
        val trimmed = kotlinTrim(query)
        val blank = kotlinBlank(trimmed)
        return posts.filter { post ->
            (!showOnlyMine || oracleIsMine(post, currentUserId)) &&
                (!showOnlyMedia || oracleHasMedia(post)) &&
                (blank || oracleMatchesSearch(post, trimmed))
        }
    }

    private fun oracleFilterMoments(
        posts: List<PostDto>,
        nowMs: Long,
        showOnlyMedia: Boolean,
        query: String
    ): List<PostDto> {
        val trimmed = kotlinTrim(query)
        val blank = kotlinBlank(trimmed)
        return posts.filter { post ->
            post.visibility == "PUBLIC" &&
                post.createdAt >= nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS &&
                (!showOnlyMedia || oracleHasMedia(post)) &&
                (blank || oracleMatchesSearch(post, trimmed))
        }
    }

    private fun oracleFilterByContent(posts: List<PostDto>, query: String): List<PostDto> {
        val trimmed = kotlinTrim(query)
        if (kotlinBlank(trimmed)) return posts
        return posts.filter { containsIgnoreCase(it.content, trimmed) }
    }

    private val nowMs = 1_700_000_000_000L

    private fun post(
        id: String,
        authorId: String,
        content: String,
        imageUrls: List<String> = emptyList(),
        visibility: String = "PUBLIC",
        createdAt: Long = nowMs,
        isMine: Boolean = false,
        authorName: String = "User $authorId"
    ) = PostDto(
        id = id,
        author = UserDto(id = authorId, name = authorName),
        content = content,
        imageUrls = imageUrls,
        visibility = visibility,
        createdAt = createdAt,
        isMine = isMine
    )

    private val words = listOf(
        "hello", "Hello", "HELLO", "goodbye", "world", "alice", "Alice",
        "Bob", "u2", "test", "", " ", "hello world"
    )

    private val visibilities = listOf("PUBLIC", "PUBLIC", "PRIVATE", "public", "PUBLIC ", "", "FRIENDS")

    private fun randomPost(rnd: Random, id: Int): PostDto {
        val offset = when (rnd.nextInt(6)) {
            0 -> rnd.nextLong(-40L * 24 * 3600 * 1000, 5L * 24 * 3600 * 1000)
            1 -> 0L
            2 -> -ExploreFeedFilterPolicy.THIRTY_DAYS_MS
            3 -> -ExploreFeedFilterPolicy.THIRTY_DAYS_MS - 1
            4 -> -ExploreFeedFilterPolicy.THIRTY_DAYS_MS + 1
            else -> 1L
        }
        val authorId = listOf("me", "u2", "u3", "me").random(rnd)
        return post(
            id = "p$id",
            authorId = authorId,
            content = if (rnd.nextInt(10) < 7) words.random(rnd) else (0 until rnd.nextInt(9)).joinToString("") { listOf("a", "B", " ", "_", "\u00DF", "\u0130").random(rnd) },
            imageUrls = List(rnd.nextInt(0, 4)) { "img$it.jpg" },
            visibility = visibilities.random(rnd),
            createdAt = nowMs + offset,
            isMine = rnd.nextInt(10) < 3,
            authorName = listOf("User $authorId", "Alice", "alice", "Bob", "").random(rnd)
        )
    }

    private fun randomQuery(rnd: Random, posts: List<PostDto>): String {
        val r = rnd.nextInt(100)
        if (r < 40 && posts.isNotEmpty()) {
            val p = posts.random(rnd)
            val fields = listOf(p.content, p.author.name, p.author.id)
            val f = fields.random(rnd)
            if (f.isEmpty()) return listOf("", " ", "a").random(rnd)
            val cps = f.codePointCount(0, f.length)
            val startCp = rnd.nextInt(cps)
            val endCp = rnd.nextInt(startCp, cps + 1)
            var q = f.substring(f.offsetByCodePoints(0, startCp), f.offsetByCodePoints(0, endCp))
            q = when (rnd.nextInt(10)) {
                in 0..2 -> q.uppercase()
                in 3..5 -> q.lowercase()
                else -> q
            }
            return q
        }
        return when {
            r < 55 -> listOf("", " ", "   ", "\t", "\n", "\u0085", "\u00A0", "\u2007").random(rnd)
            r < 85 -> words.random(rnd)
            else -> (0 until rnd.nextInt(1, 6)).joinToString("") { listOf("a", "B", " ", "_").random(rnd) }
        }
    }

    @Test
    fun isMineAndHasMediaRandomOracle() {
        val rnd = Random(44)
        repeat(300) {
            val p = randomPost(rnd, 0)
            val uid = listOf("me", "u2", "someone", "").random(rnd)
            assertEquals(oracleIsMine(p, uid), ExploreFeedFilterPolicy.isMine(p, uid), "post=$p uid=<$uid>")
            assertEquals(oracleHasMedia(p), ExploreFeedFilterPolicy.hasMedia(p), "post=$p")
        }
    }

    @Test
    fun publicRecentRandomOracle() {
        val rnd = Random(44)
        repeat(300) {
            val posts = List(rnd.nextInt(0, 9)) { randomPost(rnd, it) }
            val now = nowMs + rnd.nextLong(-1000, 1001)
            val got = ExploreFeedFilterPolicy.publicRecent(posts, now)
            assertEquals(oraclePublicRecent(posts, now), got, "posts=$posts now=$now")
            assertTrue(got.all { it.visibility == "PUBLIC" && it.createdAt >= now - ExploreFeedFilterPolicy.THIRTY_DAYS_MS })
        }
    }

    @Test
    fun filterFeedRandomOracle() {
        val rnd = Random(44)
        repeat(200) {
            val posts = List(rnd.nextInt(0, 9)) { randomPost(rnd, it) }
            val uid = listOf("me", "u2", "someone", "").random(rnd)
            val mine = rnd.nextBoolean()
            val media = rnd.nextBoolean()
            val query = randomQuery(rnd, posts)
            val got = ExploreFeedFilterPolicy.filterFeed(posts, uid, mine, media, query)
            assertEquals(oracleFilterFeed(posts, uid, mine, media, query), got, "uid=<$uid> mine=$mine media=$media query=<$query> posts=$posts")
            // Result is a subsequence of the input, in order.
            var idx = 0
            for (kept in got) {
                var found = false
                while (idx < posts.size) {
                    if (posts[idx] == kept) {
                        found = true
                        idx++
                        break
                    }
                    idx++
                }
                assertTrue(found, "kept $kept is not a subsequence of $posts")
            }
            if (!mine && !media && kotlinBlank(kotlinTrim(query))) {
                assertEquals(posts, got, "all flags off and blank query returns the input")
            }
        }
    }

    @Test
    fun filterMomentsRandomOracle() {
        val rnd = Random(44)
        repeat(200) {
            val posts = List(rnd.nextInt(0, 9)) { randomPost(rnd, it) }
            val now = nowMs + rnd.nextLong(-1000, 1001)
            val media = rnd.nextBoolean()
            val query = randomQuery(rnd, posts)
            val got = ExploreFeedFilterPolicy.filterMoments(posts, now, media, query)
            assertEquals(oracleFilterMoments(posts, now, media, query), got, "now=$now media=$media query=<$query> posts=$posts")
            assertTrue(got.all { it.visibility == "PUBLIC" && it.createdAt >= now - ExploreFeedFilterPolicy.THIRTY_DAYS_MS })
        }
    }

    @Test
    fun filterByContentRandomOracle() {
        val rnd = Random(44)
        repeat(200) {
            val posts = List(rnd.nextInt(0, 9)) { randomPost(rnd, it) }
            val query = randomQuery(rnd, posts)
            assertEquals(
                oracleFilterByContent(posts, query),
                ExploreFeedFilterPolicy.filterByContent(posts, query),
                "query=<$query> posts=$posts"
            )
        }
    }

    @Test
    fun publicRecentWindowBoundaryAndCaseSensitiveVisibility() {
        val boundary = post("b", "u5", "edge", createdAt = nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS)
        val justOld = post("o", "u6", "old", createdAt = nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS - 1)
        val justInside = post("i", "u7", "in", createdAt = nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS + 1)
        assertEquals(listOf(boundary, justInside), ExploreFeedFilterPolicy.publicRecent(listOf(boundary, justOld, justInside), nowMs))
        // Visibility is a case-sensitive == "PUBLIC": lowercase and padded forms drop out.
        assertEquals(emptyList(), ExploreFeedFilterPolicy.publicRecent(listOf(post("p", "u8", "x", visibility = "public")), nowMs))
        assertEquals(emptyList(), ExploreFeedFilterPolicy.publicRecent(listOf(post("p", "u8", "x", visibility = "PUBLIC ")), nowMs))
        assertEquals(emptyList(), ExploreFeedFilterPolicy.publicRecent(listOf(post("p", "u8", "x", visibility = "")), nowMs))
        // isMine works through either channel: the flag or the author id.
        val flagged = post("f", "u9", "hello", isMine = true)
        val byAuthor = post("a", "me", "hello")
        val neither = post("n", "u9", "hello")
        assertTrue(ExploreFeedFilterPolicy.isMine(flagged, "someone"))
        assertTrue(ExploreFeedFilterPolicy.isMine(byAuthor, "me"))
        assertEquals(false, ExploreFeedFilterPolicy.isMine(neither, "me"))
        // All flags off and a blank query returns the input untouched.
        val posts = listOf(flagged, byAuthor, neither)
        assertEquals(posts, ExploreFeedFilterPolicy.filterFeed(posts, "me", false, false, "  "))
        assertEquals(listOf(flagged, byAuthor), ExploreFeedFilterPolicy.filterFeed(posts, "me", true, false, "hello"))
        // Moments always stay public and inside the window, whatever the query.
        val old = post("old", "u9", "hello", createdAt = nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS - 1)
        assertEquals(listOf(flagged, byAuthor), ExploreFeedFilterPolicy.filterMoments(listOf(flagged, byAuthor, old), nowMs, false, ""))
    }
}
