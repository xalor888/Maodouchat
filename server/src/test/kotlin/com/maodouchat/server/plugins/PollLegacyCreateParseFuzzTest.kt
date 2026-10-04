package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollLegacyCreateParseFuzzTest {

    private companion object {
        private val KNOWN_CREATE_FIELDS = setOf("question", "options", "multi", "anonymous", "closesAt")
        private val KNOWN_VOTE_FIELDS = setOf("optionIndexes", "optionIndex")
        private const val ITERATIONS = 150
    }

    // 旧内联写法的逐字复刻（return@post 换成 return null），只供等价性比对。
    private fun oldCreateWay(obj: JsonObject): PollLegacyCreateFields? {
        val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
        val options = buildList {
            val arr = obj["options"]?.jsonArray
            if (arr != null) {
                for (element in arr) {
                    val text = (element as? JsonPrimitive)?.content ?: return null
                    add(text)
                }
            }
        }
        val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
            ?: obj["multi"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: false
        val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
            ?: obj["anonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: false
        val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
        return PollLegacyCreateFields(question, options, multi, anonymous, closesAt)
    }

    private fun oldVoteWay(obj: JsonObject): List<Int>? {
        return buildList {
            val arr = obj["optionIndexes"]?.jsonArray
            if (arr != null) {
                for (element in arr) {
                    val v = (element as? JsonPrimitive)?.content?.toIntOrNull()
                    if (v == null || v < 0) return null
                    add(v)
                }
            } else {
                val single = (obj["optionIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (single == null || single < 0) return null
                add(single)
            }
        }
    }

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
            else -> JsonObject(mapOf(randomFieldName(random, KNOWN_CREATE_FIELDS + KNOWN_VOTE_FIELDS) to randomScalar(random, false)))
        }

    private fun randomCreateFieldValue(random: Random, name: String): JsonElement = when (name) {
        "question" -> randomScalar(random, allowContainers = true)
        "options" -> if (random.nextBoolean()) {
            JsonArray(List(random.nextInt(0, 5)) { randomScalar(random, allowContainers = true) })
        } else {
            randomScalar(random, allowContainers = true)
        }
        "multi", "anonymous" -> when (random.nextInt(6)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(if (random.nextBoolean()) "true" else "false")
            2 -> JsonPrimitive("yes")
            3 -> JsonPrimitive(random.nextInt())
            4 -> JsonNull
            else -> randomScalar(random, allowContainers = true)
        }
        "closesAt" -> when (random.nextInt(5)) {
            0 -> JsonPrimitive(random.nextLong(1_000_000L, 9_999_999_999L))
            1 -> JsonPrimitive(random.nextLong(1_000_000L, 9_999_999_999L).toString())
            2 -> JsonPrimitive("not-a-number")
            3 -> JsonNull
            else -> randomScalar(random, allowContainers = true)
        }
        else -> error("unknown field $name")
    }

    private fun randomVoteFieldValue(random: Random, name: String): JsonElement = when (name) {
        "optionIndexes" -> when (random.nextInt(5)) {
            0 -> JsonArray(List(random.nextInt(0, 4)) {
                when (random.nextInt(6)) {
                    0 -> JsonPrimitive(random.nextInt(0, 10))
                    1 -> JsonPrimitive(-random.nextInt(1, 10))
                    2 -> JsonPrimitive(random.nextInt(0, 10).toString())
                    3 -> JsonPrimitive("abc")
                    4 -> JsonNull
                    else -> JsonPrimitive(random.nextBoolean())
                }
            })
            1 -> JsonPrimitive(random.nextInt(0, 10))
            2 -> JsonObject(mapOf(randomFieldName(random, KNOWN_VOTE_FIELDS) to JsonPrimitive(1)))
            3 -> JsonNull
            else -> JsonPrimitive("7")
        }
        "optionIndex" -> when (random.nextInt(5)) {
            0 -> JsonPrimitive(random.nextInt(0, 10))
            1 -> JsonPrimitive(-random.nextInt(1, 10))
            2 -> JsonPrimitive("3")
            3 -> JsonNull
            else -> JsonPrimitive(random.nextBoolean())
        }
        else -> error("unknown field $name")
    }

    private fun assertCreateSameAsOld(obj: JsonObject, iteration: Int) {
        val old = runCatching { oldCreateWay(obj) }
        val new = runCatching { parsePollLegacyCreateOrNull(obj) }
        assertEquals(old.getOrNull(), new.getOrNull(), "创建解析 fuzz #$iteration：值不一致")
        assertEquals(
            old.exceptionOrNull()?.let { it::class },
            new.exceptionOrNull()?.let { it::class },
            "创建解析 fuzz #$iteration：异常不一致",
        )
    }

    private fun assertVoteSameAsOld(obj: JsonObject, iteration: Int) {
        val old = runCatching { oldVoteWay(obj) }
        val new = runCatching { parsePollLegacyVoteOrNull(obj) }
        assertEquals(old.getOrNull(), new.getOrNull(), "投票解析 fuzz #$iteration：值不一致")
        assertEquals(
            old.exceptionOrNull()?.let { it::class },
            new.exceptionOrNull()?.let { it::class },
            "投票解析 fuzz #$iteration：异常不一致",
        )
    }

    @Test
    fun `legacy poll create parse survives seeded fuzz`() {
        val random = Random(2026100401)
        repeat(ITERATIONS) { i ->
            val fields = mutableMapOf<String, JsonElement>()
            for (name in KNOWN_CREATE_FIELDS) {
                if (random.nextBoolean()) fields[name] = randomCreateFieldValue(random, name)
            }
            repeat(random.nextInt(0, 5)) {
                fields[randomFieldName(random, KNOWN_CREATE_FIELDS)] = randomScalar(random, true)
            }
            assertCreateSameAsOld(JsonObject(fields), i)
        }
    }

    @Test
    fun `legacy poll vote parse survives seeded fuzz`() {
        val random = Random(2026100402)
        repeat(ITERATIONS) { i ->
            val fields = mutableMapOf<String, JsonElement>()
            for (name in KNOWN_VOTE_FIELDS) {
                if (random.nextBoolean()) fields[name] = randomVoteFieldValue(random, name)
            }
            repeat(random.nextInt(0, 5)) {
                fields[randomFieldName(random, KNOWN_VOTE_FIELDS)] = randomScalar(random, true)
            }
            assertVoteSameAsOld(JsonObject(fields), i)
        }
    }

    @Test
    fun `create rejects non-primitive option elements`() {
        val obj = JsonObject(
            mapOf(
                "question" to JsonPrimitive("q"),
                "options" to JsonArray(listOf(JsonPrimitive("a"), JsonObject(mapOf("x" to JsonPrimitive(1))))),
            )
        )
        assertNull(parsePollLegacyCreateOrNull(obj), "选项含对象元素必须整体拒绝")
        assertNull(oldCreateWay(obj), "旧写法同样拒绝")
    }

    @Test
    fun `create string booleans stay compatible`() {
        val obj = JsonObject(
            mapOf(
                "multi" to JsonPrimitive("true"),
                "anonymous" to JsonPrimitive("yes"),
            )
        )
        val fields = parsePollLegacyCreateOrNull(obj)
        assertTrue(fields != null, "合法体必须解析成功")
        assertEquals(true, fields.multi, "\"true\" 字符串保持 true")
        assertEquals(false, fields.anonymous, "\"yes\" 非严格布尔，保持 false")
    }

    @Test
    fun `create wrong-type scalar still throws`() {
        val obj = JsonObject(mapOf("question" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>("question 传对象必须大声失败（路由层 StatusPages 映射 400）") {
            parsePollLegacyCreateOrNull(obj)
        }
    }

    @Test
    fun `vote rejects bad indexes`() {
        assertNull(
            parsePollLegacyVoteOrNull(JsonObject(mapOf("optionIndexes" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive("abc"), JsonPrimitive(1)))))),
            "[0,\"abc\",1] 必须整体拒绝，不静默投成子集",
        )
        assertNull(
            parsePollLegacyVoteOrNull(JsonObject(mapOf("optionIndexes" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(-2)))))),
            "负数索引必须拒绝",
        )
        assertNull(
            parsePollLegacyVoteOrNull(JsonObject(emptyMap())),
            "两键都缺席必须拒绝",
        )
        assertEquals(
            listOf(2),
            parsePollLegacyVoteOrNull(JsonObject(mapOf("optionIndex" to JsonPrimitive(2)))),
            "单 optionIndex=2 保持 [2]",
        )
    }

    @Test
    fun `vote wrong-type optionIndexes still throws`() {
        val obj = JsonObject(mapOf("optionIndexes" to JsonObject(mapOf("x" to JsonPrimitive(1)))))
        assertFailsWith<IllegalArgumentException>("optionIndexes 传对象必须大声失败") {
            parsePollLegacyVoteOrNull(obj)
        }
    }
}
