package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdQueryParamParseFuzzTest {

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
    fun `attachment friend developer id orEmpty inline and shared parse agree on seeded random params`() {
        val random = Random(35601)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random, "id", "friendId")
            for (name in listOf("id", "friendId")) {
                assertEquals(
                    oldWay(params, name),
                    parseRawOrEmpty(params, name),
                    "iteration " + i + ": " + name + " must match",
                )
            }
        }
    }

    @Test
    fun `id orEmpty boundaries are pinned`() {
        // 缺省回空串，下游按 isBlank/判等处理缺参（好友解除走 400，附件查不到走 404，bot 鉴权不匹配走 403）。
        assertEquals("", parseRawOrEmpty(parametersOf(), "id"))
        assertEquals("", parseRawOrEmpty(parametersOf(), "friendId"))
        // 空串/全空白原样透出，下游自己做 blank 判定（不 trim、不过滤）。
        assertEquals("", parseRawOrEmpty(parametersOf("id" to listOf("")), "id"))
        assertEquals("   ", parseRawOrEmpty(parametersOf("id" to listOf("   ")), "id"))
        assertTrue(parseRawOrEmpty(parametersOf("friendId" to listOf("   ")), "friendId").isBlank())
        // 正常取值原样透出。
        assertEquals("a1", parseRawOrEmpty(parametersOf("id" to listOf("a1")), "id"))
        assertEquals("f2", parseRawOrEmpty(parametersOf("friendId" to listOf("f2")), "friendId"))
    }
}
