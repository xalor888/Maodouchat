package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotChatIdQueryParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomParamValue(random: Random): String = when (random.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> "  abc  "
        3 -> "1"
        4 -> "0"
        5 -> "true"
        6 -> "false"
        7 -> "-5"
        8 -> "9223372036854775807"
        else -> "x" + random.nextInt(100000)
    }

    private fun randomParams(random: Random, vararg names: String): Parameters {
        val pairs = mutableListOf<Pair<String, List<String>>>()
        names.forEach { name ->
            if (random.nextBoolean()) pairs += name to listOf(randomParamValue(random))
        }
        return parametersOf(*pairs.toTypedArray())
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldWay(params: Parameters, name: String): String =
        params[name].orEmpty()

    @Test
    fun `bot chat member geo poll orEmpty inline and shared parse agree on seeded random params`() {
        val random = Random(35412)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random, "chatId", "userId", "pollId")
            for (name in listOf("chatId", "userId", "pollId")) {
                assertEquals(
                    oldWay(params, name),
                    parseRawOrEmpty(params, name),
                    "iteration " + i + ": " + name + " must match",
                )
            }
        }
    }

    @Test
    fun `bot orEmpty boundaries are pinned`() {
        // 缺省回空串，下游按 isBlank 判定缺参（getChat/getChatMember 等仍报 required）。
        assertEquals("", parseRawOrEmpty(parametersOf(), "chatId"))
        // 空串/全空白原样透出，下游自己做 blank 判定（不 trim、不过滤）。
        assertEquals("", parseRawOrEmpty(parametersOf("chatId" to listOf("")), "chatId"))
        assertEquals("   ", parseRawOrEmpty(parametersOf("chatId" to listOf("   ")), "chatId"))
        assertTrue(parseRawOrEmpty(parametersOf("chatId" to listOf("   ")), "chatId").isBlank())
        // 正常取值原样透出。
        assertEquals("g123", parseRawOrEmpty(parametersOf("chatId" to listOf("g123")), "chatId"))
        assertEquals("u1", parseRawOrEmpty(parametersOf("userId" to listOf("u1")), "userId"))
        assertEquals("p9", parseRawOrEmpty(parametersOf("pollId" to listOf("p9")), "pollId"))
    }
}
