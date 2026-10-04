package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiscIdQueryParamParseFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private val names = listOf(
        "userId", "uid", "reportId", "ruleId", "eventId",
        "packId", "name", "filename", "abi",
    )

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
    fun `misc id orEmpty inline and shared parse agree on seeded random params`() {
        val random = Random(35801)
        repeat(ITERATIONS) { i ->
            val params = randomParams(random, *names.toTypedArray())
            for (name in names) {
                assertEquals(
                    oldWay(params, name),
                    parseRawOrEmpty(params, name),
                    "iteration " + i + ": " + name + " must match",
                )
            }
        }
    }

    @Test
    fun `misc id orEmpty boundaries are pinned`() {
        // 缺省回空串：下游全部按资源查不到收敛——密钥 404（查无设备/密钥）、拉黑走失败映射 400、
        // 举报/规则/事件 404、贴纸 400（白名单后为空）、图片名走归属判定 400、abi 不支持 404。
        for (name in names) {
            assertEquals("", parseRawOrEmpty(parametersOf(), name))
        }
        // 空串/全空白原样透出，下游自己做 blank 判定（不 trim、不过滤）。
        assertEquals("", parseRawOrEmpty(parametersOf("userId" to listOf("")), "userId"))
        assertEquals("   ", parseRawOrEmpty(parametersOf("reportId" to listOf("   ")), "reportId"))
        assertTrue(parseRawOrEmpty(parametersOf("packId" to listOf("   ")), "packId").isBlank())
        // 正常取值原样透出。
        assertEquals("u1", parseRawOrEmpty(parametersOf("userId" to listOf("u1")), "userId"))
        assertEquals("r2", parseRawOrEmpty(parametersOf("reportId" to listOf("r2")), "reportId"))
        assertEquals("aarch64", parseRawOrEmpty(parametersOf("abi" to listOf("aarch64")), "abi"))
        assertEquals("f3.png", parseRawOrEmpty(parametersOf("filename" to listOf("f3.png")), "filename"))
    }
}
