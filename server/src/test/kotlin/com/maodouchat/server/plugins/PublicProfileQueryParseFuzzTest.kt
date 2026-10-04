package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicProfileQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomUsername(random: Random): String = when (random.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> "@"
        3 -> "  @Alice  "
        4 -> "BOB"
        5 -> "carol_123"
        6 -> "@@x"
        7 -> "中 文"
        8 -> "a"
        else -> "u" + random.nextInt(100000)
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldWay(params: Parameters, name: String): String =
        params[name]?.trim()?.lowercase()?.removePrefix("@").orEmpty()

    @Test
    fun `public profile username normalize inline and shared parse agree on seeded random params`() {
        val random = Random(36119)
        repeat(ITERATIONS) { i ->
            val params = if (random.nextBoolean()) {
                parametersOf("username" to listOf(randomUsername(random)))
            } else {
                parametersOf()
            }
            assertEquals(
                oldWay(params, "username"),
                parseProfileUsername(params, "username"),
                "iteration " + i + " must match",
            )
        }
    }

    @Test
    fun `public profile username normalize boundaries are pinned`() {
        // 缺省/空值回空串，下游走 400「用户名无效」。
        assertEquals("", parseProfileUsername(parametersOf(), "username"))
        assertEquals("", parseProfileUsername(parametersOf("username" to listOf("")), "username"))
        assertEquals("", parseProfileUsername(parametersOf("username" to listOf("   ")), "username"))
        // 纯 @ 去掉前缀后回空串。
        assertEquals("", parseProfileUsername(parametersOf("username" to listOf("@")), "username"))
        // trim + 小写 + 去单个 @ 前缀。
        assertEquals("alice", parseProfileUsername(parametersOf("username" to listOf("  @Alice  ")), "username"))
        assertEquals("bob", parseProfileUsername(parametersOf("username" to listOf("BOB")), "username"))
        // removePrefix 只去一个 @。
        assertEquals("@x", parseProfileUsername(parametersOf("username" to listOf("@@x")), "username"))
        // 正常值原样透出（小写除外）。
        assertEquals("carol_123", parseProfileUsername(parametersOf("username" to listOf("carol_123")), "username"))
        assertTrue(parseProfileUsername(parametersOf("username" to listOf("ab")), "username").length == 2)
    }
}
