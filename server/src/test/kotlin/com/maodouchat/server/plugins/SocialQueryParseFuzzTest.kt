package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SocialQueryParseFuzzTest {

    private companion object {
        private const val SEED = 19342
        private const val ITERATIONS = 150
        private val NOISE_NAMES = listOf("beforeId", "authorId", "chatId", "foo", "x")
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~\t\n"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(9)) {
        0 -> ""
        1 -> "   "
        2 -> random.nextInt(-500, 5000).toString()
        3 -> random.nextDouble(-50.0, 25000.0).toString()
        4 -> randomString(random, random.nextInt(1, 30))
        5 -> random.nextLong(-100_000L, 9_000_000_000_000L).toString()
        6 -> "abc"
        7 -> "99999999999999999999999"
        else -> random.nextLong(-5, 5).toString() + ".5"
    }

    private fun randomParams(random: Random): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        if (random.nextBoolean()) pairs += "limit" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "before" to listOf(randomParamValue(random))
        if (random.nextBoolean()) pairs += "status" to listOf(randomStatusValue(random))
        if (random.nextBoolean()) pairs += "needsReview" to listOf(randomReviewValue(random))
        repeat(random.nextInt(0, 3)) { pairs += NOISE_NAMES.random(random) to listOf(randomParamValue(random)) }
        return parametersOf(*pairs.toTypedArray())
    }

    private fun randomStatusValue(random: Random): String = when (random.nextInt(6)) {
        0 -> "PENDING"
        1 -> "ACCEPTED"
        2 -> "REJECTED"
        3 -> ""
        4 -> "pending"
        else -> randomParamValue(random)
    }

    private fun randomReviewValue(random: Random): String = when (random.nextInt(7)) {
        0 -> "true"
        1 -> "false"
        2 -> "TRUE"
        3 -> "False"
        4 -> "yes"
        5 -> ""
        else -> randomParamValue(random)
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldBeforeWay(params: Parameters): Long? =
        params["before"]?.toLongOrNull()

    private fun oldGroupPlayLimitWay(params: Parameters, defaultLimit: Int): Int =
        params["limit"]?.toIntOrNull() ?: defaultLimit

    private fun oldFriendStatusWay(params: Parameters): String =
        params["status"] ?: "PENDING"

    private fun oldNeedsReviewWay(params: Parameters): Boolean? =
        params["needsReview"]?.toBooleanStrictOrNull()

    @Test
    fun `feed before matches old inline`() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldBeforeWay(params), parseSocialFeedBefore(params), "iter $i")
        }
    }

    @Test
    fun `group play limit matches old inline`() {
        val random = Random(SEED + 1)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldGroupPlayLimitWay(params, 30), parseGroupPlayLimit(params), "iter $i default30")
            assertEquals(oldGroupPlayLimitWay(params, 20), parseGroupPlayLimit(params, defaultLimit = 20), "iter $i default20")
        }
    }

    @Test
    fun `friend request status matches old inline`() {
        val random = Random(SEED + 2)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldFriendStatusWay(params), parseFriendRequestStatus(params), "iter $i")
        }
    }

    @Test
    fun `needs review matches old inline`() {
        val random = Random(SEED + 3)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random)
            assertEquals(oldNeedsReviewWay(params), parseNeedsReview(params), "iter $i")
        }
    }

    @Test
    fun `pinned semantics`() {
        // before：缺省/非法为 null，负值与超大值原样透传。
        assertNull(parseSocialFeedBefore(parametersOf()))
        assertNull(parseSocialFeedBefore(parametersOf("before" to listOf("abc"))))
        assertEquals(-5L, parseSocialFeedBefore(parametersOf("before" to listOf("-5"))))
        assertNull(parseSocialFeedBefore(parametersOf("before" to listOf("99999999999999999999999"))))
        // group play limit：无钳制，负值原样透传，非法回默认。
        assertEquals(30, parseGroupPlayLimit(parametersOf()))
        assertEquals(30, parseGroupPlayLimit(parametersOf("limit" to listOf("abc"))))
        assertEquals(-7, parseGroupPlayLimit(parametersOf("limit" to listOf("-7"))))
        assertEquals(20, parseGroupPlayLimit(parametersOf(), defaultLimit = 20))
        assertEquals(Int.MAX_VALUE, parseGroupPlayLimit(parametersOf("limit" to listOf(Int.MAX_VALUE.toString()))))
        // friend status：缺省回 PENDING，空串原样透传（旧内联也是如此）。
        assertEquals("PENDING", parseFriendRequestStatus(parametersOf()))
        assertEquals("", parseFriendRequestStatus(parametersOf("status" to listOf(""))))
        assertEquals("ACCEPTED", parseFriendRequestStatus(parametersOf("status" to listOf("ACCEPTED"))))
        // needsReview：严格布尔，非法值回 null。
        assertNull(parseNeedsReview(parametersOf()))
        assertEquals(true, parseNeedsReview(parametersOf("needsReview" to listOf("true"))))
        assertEquals(false, parseNeedsReview(parametersOf("needsReview" to listOf("false"))))
        assertNull(parseNeedsReview(parametersOf("needsReview" to listOf("TRUE"))))
        assertNull(parseNeedsReview(parametersOf("needsReview" to listOf("yes"))))
        assertNull(parseNeedsReview(parametersOf("needsReview" to listOf(""))))
    }
}
