package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupIdQueryParamParseFuzzTest {

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
    fun `group admin and invitation routes id orEmpty inline and shared parse agree on seeded random params`() {
        val random = Random(35701)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random, "chatId", "memberId", "inviteId")
            for (name in listOf("chatId", "memberId", "inviteId")) {
                assertEquals(
                    oldWay(params, name),
                    parseRawOrEmpty(params, name),
                    "iteration " + i + ": " + name + " must match",
                )
            }
        }
    }

    @Test
    fun `group id orEmpty boundaries are pinned`() {
        // 缺省回空串，下游按成员/邀请查不到收敛为 403/404（成员添删走权限/失败映射，邀请走 404/409）。
        assertEquals("", parseRawOrEmpty(parametersOf(), "chatId"))
        assertEquals("", parseRawOrEmpty(parametersOf(), "memberId"))
        assertEquals("", parseRawOrEmpty(parametersOf(), "inviteId"))
        // 空串/全空白原样透出，下游自己做 blank 判定（不 trim、不过滤）。
        assertEquals("", parseRawOrEmpty(parametersOf("chatId" to listOf("")), "chatId"))
        assertEquals("   ", parseRawOrEmpty(parametersOf("memberId" to listOf("   ")), "memberId"))
        assertTrue(parseRawOrEmpty(parametersOf("inviteId" to listOf("   ")), "inviteId").isBlank())
        // 正常取值原样透出。
        assertEquals("c1", parseRawOrEmpty(parametersOf("chatId" to listOf("c1")), "chatId"))
        assertEquals("m2", parseRawOrEmpty(parametersOf("memberId" to listOf("m2")), "memberId"))
        assertEquals("i3", parseRawOrEmpty(parametersOf("inviteId" to listOf("i3")), "inviteId"))
    }
}
