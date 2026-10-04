package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PollPlayParseFuzzTest {

    private companion object {
        private val KNOWN_CHAIN_CREATE = setOf("title", "topic", "maxEntries")
        private val KNOWN_PK_CREATE = setOf("leftTitle", "rightTitle")
        private const val ITERATIONS = 150
    }

    // 旧内联写法的逐字复刻，只供等价性比对。
    private fun oldChainCreateWay(obj: JsonObject): ChainCreateFields {
        val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
        val topic = obj["topic"]?.jsonPrimitive?.content.orEmpty()
        val maxEntries = obj["maxEntries"]?.jsonPrimitive?.intOrNull ?: 200
        return ChainCreateFields(title, topic, maxEntries)
    }

    private fun oldChainJoinWay(obj: JsonObject): String =
        obj["content"]?.jsonPrimitive?.content.orEmpty()

    private fun oldPkCreateWay(obj: JsonObject): PkCreateFields {
        val leftTitle = obj["leftTitle"]?.jsonPrimitive?.content.orEmpty()
        val rightTitle = obj["rightTitle"]?.jsonPrimitive?.content.orEmpty()
        return PkCreateFields(leftTitle, rightTitle)
    }

    private fun oldPkVoteWay(obj: JsonObject): String =
        obj["choice"]?.jsonPrimitive?.content.orEmpty()

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomFieldName(random: Random, known: Set<String>): String {
        var name = "fuzz_" + randomString(random, random.nextInt(3, 10)).replace(" ", "_")
        while (name in known) name = "z$name"
        return name
    }

    private fun randomScalar(random: Random, allowContainers: Boolean): JsonElement =
        when (random.nextInt(if (allowContainers) 7 else 5)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong(-1000, 1000))
            2 -> JsonPrimitive(randomString(random, random.nextInt(0, 24)))
            3 -> JsonNull
            4 -> JsonPrimitive(random.nextDouble())
            5 -> JsonArray(List(random.nextInt(0, 3)) { randomScalar(random, false) })
            else -> JsonObject(mapOf(randomFieldName(random, emptySet()) to randomScalar(random, false)))
        }

    private fun randomMaxEntries(random: Random): JsonElement = when (random.nextInt(8)) {
        0 -> JsonPrimitive(random.nextInt(1, 500))
        1 -> JsonPrimitive(random.nextInt(1, 500).toString())
        2 -> JsonPrimitive("not-a-number")
        3 -> JsonPrimitive(random.nextBoolean())
        4 -> JsonNull
        5 -> JsonArray(listOf(JsonPrimitive(1)))
        6 -> JsonObject(mapOf("x" to JsonPrimitive(1)))
        else -> randomScalar(random, false)
    }

    private fun <T> assertSameAsOld(
        obj: JsonObject,
        old: (JsonObject) -> T,
        new: (JsonObject) -> T,
        label: String,
        iteration: Int,
    ) {
        val oldResult = runCatching { old(obj) }
        val newResult = runCatching { new(obj) }
        assertEquals(oldResult.getOrNull(), newResult.getOrNull(), label + " fuzz #" + iteration + "：值不一致")
        assertEquals(
            oldResult.exceptionOrNull()?.let { it::class },
            newResult.exceptionOrNull()?.let { it::class },
            label + " fuzz #" + iteration + "：异常不一致",
        )
    }

    private fun randomObject(random: Random, known: Set<String>, valueOf: (Random, String) -> JsonElement): JsonObject {
        val fields = mutableMapOf<String, JsonElement>()
        for (name in known) {
            if (random.nextBoolean()) fields[name] = valueOf(random, name)
        }
        repeat(random.nextInt(0, 5)) {
            fields[randomFieldName(random, known)] = randomScalar(random, true)
        }
        return JsonObject(fields)
    }

    @Test
    fun `chain create parse survives seeded fuzz`() {
        val random = Random(2026100403)
        repeat(ITERATIONS) { i ->
            val obj = randomObject(random, KNOWN_CHAIN_CREATE) { r, name ->
                if (name == "maxEntries") randomMaxEntries(r) else randomScalar(r, true)
            }
            assertSameAsOld(obj, ::oldChainCreateWay, ::parseChainCreateFields, "接龙创建解析", i)
        }
    }

    @Test
    fun `chain join content parse survives seeded fuzz`() {
        val random = Random(2026100404)
        repeat(ITERATIONS) { i ->
            val obj = randomObject(random, setOf("content")) { r, _ -> randomScalar(r, true) }
            assertSameAsOld(obj, ::oldChainJoinWay, ::parseChainJoinContent, "接龙参与解析", i)
        }
    }

    @Test
    fun `pk create parse survives seeded fuzz`() {
        val random = Random(2026100405)
        repeat(ITERATIONS) { i ->
            val obj = randomObject(random, KNOWN_PK_CREATE) { r, _ -> randomScalar(r, true) }
            assertSameAsOld(obj, ::oldPkCreateWay, ::parsePkCreateFields, "PK 创建解析", i)
        }
    }

    @Test
    fun `pk vote choice parse survives seeded fuzz`() {
        val random = Random(2026100406)
        repeat(ITERATIONS) { i ->
            val obj = randomObject(random, setOf("choice")) { r, _ -> randomScalar(r, true) }
            assertSameAsOld(obj, ::oldPkVoteWay, ::parsePkVoteChoice, "PK 投票解析", i)
        }
    }

    @Test
    fun `maxEntries defaults and string form pinned`() {
        val missing = parseChainCreateFields(JsonObject(emptyMap()))
        assertEquals(200, missing.maxEntries, "maxEntries 缺省应回 200")
        val stringForm = parseChainCreateFields(JsonObject(mapOf("maxEntries" to JsonPrimitive("50"))))
        assertEquals(50, stringForm.maxEntries, "maxEntries 字符串形态应解析")
        val garbage = parseChainCreateFields(JsonObject(mapOf("maxEntries" to JsonPrimitive("abc"))))
        assertEquals(200, garbage.maxEntries, "maxEntries 非法应回 200")
    }

    @Test
    fun `wrong-typed fields fail loudly in both`() {
        val obj = JsonObject(
            mapOf(
                "title" to JsonArray(listOf(JsonPrimitive("a"))),
                "maxEntries" to JsonPrimitive(10),
            )
        )
        assertFailsWith<IllegalArgumentException>("title 传数组旧写法应大声失败") { oldChainCreateWay(obj) }
        assertFailsWith<IllegalArgumentException>("title 传数组新函数应同样大声失败") { parseChainCreateFields(obj) }
        val obj2 = JsonObject(mapOf("choice" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>("choice 传对象旧写法应大声失败") { oldPkVoteWay(obj2) }
        assertFailsWith<IllegalArgumentException>("choice 传对象新函数应同样大声失败") { parsePkVoteChoice(obj2) }
    }
}
