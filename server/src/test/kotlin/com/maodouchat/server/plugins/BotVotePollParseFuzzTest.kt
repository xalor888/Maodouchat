package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Bot `votePoll` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `echo` 之后第七十四块）。
 *
 * 本测试直接钉住纯函数 ([parseBotVotePollFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 钉住 **optionIndexes 数组优先于 optionIndex**：两键并存时数组赢，单字段被忽略。
 * - 钉住 **非数组型 optionIndexes 回退单字段**（`as? JsonArray` 判 null）：字符串型、
 *   显式 null 均走 `optionIndex` 分支，不是 400。
 * - 钉住 **空数组 `[]` → Required**：逐元素校验放行空列表，合并必填兜底。
 * - 钉住 **pollId 无 trim**：`" p1 "` 原样保留仍通过必填（`isBlank()` 只判空）。
 * - 钉住 **显式 null 得字面量 `"null"`**：`JsonNull` 是 `JsonPrimitive`，
 *   `.content` 为 `"null"`——不是类型错，非空 → `Ok`。
 * - 反证坏类型大声失败：对象 / 数组型 pollId 在 `?.jsonPrimitive`
 *   处抛 [IllegalArgumentException]（路由层 `StatusPages` 映射为 400「参数无效」，
 *   不是 500）。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotVotePollParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "pollId",
            "optionIndexes",
            "optionIndex",
        )
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotVotePollFieldsResult =
        parseBotVotePollFields(obj)

    /** 双字段端点：每个 Ok 用例都携带全部必填字段（吸取第五十七块 CI 教训）。 */
    private fun okOf(pollId: String, indexes: List<Int>): BotVotePollFields =
        (parseOf(
            JsonObject(
                mapOf(
                    "pollId" to JsonPrimitive(pollId),
                    "optionIndexes" to JsonArray(indexes.map { JsonPrimitive(it) }),
                )
            )
        ) as BotVotePollFieldsResult.Ok).fields

    private fun randomScalar(random: Random): JsonElement = when (random.nextInt(7)) {
        0 -> JsonPrimitive(random.nextBoolean())
        1 -> JsonPrimitive(random.nextInt(-1000, 1000))
        2 -> JsonPrimitive(random.nextDouble(-1000.0, 1000.0))
        3 -> JsonPrimitive(randomString(random, random.nextInt(0, 40)))
        4 -> JsonNull
        5 -> JsonArray(List(random.nextInt(0, 4)) { randomScalar(random) })
        else -> JsonObject(mapOf(randomName(random) to randomScalar(random)))
    }

    private fun randomString(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-.,!?~"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomName(random: Random): String {
        var name: String
        do {
            name = "fuzz_" + randomString(random, random.nextInt(3, 12)).replace(" ", "_")
        } while (name in KNOWN_FIELD_NAMES)
        return name
    }

    @Test
    fun fuzzUnknownKeysIgnored() {
        val random = Random(2026100374)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 基 payload：pollId 恒为 "p<i>"，索引恒为 [i%3, (i+1)%3]。
            val expectedId = "p" + i
            val expectedIndexes = listOf(i % 3, (i + 1) % 3)
            base["pollId"] = JsonPrimitive(expectedId)
            base["optionIndexes"] = JsonArray(expectedIndexes.map { JsonPrimitive(it) })
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val viaParse = parseOf(JsonObject(base))
            assertTrue(viaParse is BotVotePollFieldsResult.Ok, "注入未知键后仍应为 Ok，迭代 " + i)
            assertEquals(expectedId, viaParse.fields.pollId, "未知键不得污染 pollId，迭代 " + i)
            assertEquals(expectedIndexes, viaParse.fields.optionIndexes, "未知键不得污染 optionIndexes，迭代 " + i)
        }
    }

    @Test
    fun requiredSemantics() {
        // pollId 缺席 → Required。
        assertTrue(
            parseOf(
                JsonObject(mapOf("optionIndexes" to JsonArray(listOf(JsonPrimitive(0)))))
            ) is BotVotePollFieldsResult.Required,
            "pollId 缺席应为 Required",
        )
        // pollId 纯空白 → Required。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "pollId" to JsonPrimitive("   "),
                        "optionIndexes" to JsonArray(listOf(JsonPrimitive(1))),
                    )
                )
            ) is BotVotePollFieldsResult.Required,
            "pollId 纯空白应为 Required",
        )
        // optionIndexes 空数组 → Required（逐元素校验放行空列表，合并必填兜底）。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "pollId" to JsonPrimitive("p1"),
                        "optionIndexes" to JsonArray(emptyList()),
                    )
                )
            ) is BotVotePollFieldsResult.Required,
            "optionIndexes 为空数组应为 Required",
        )
        // 索引双缺 → Required。
        assertTrue(
            parseOf(JsonObject(mapOf("pollId" to JsonPrimitive("p1"))))
                is BotVotePollFieldsResult.Required,
            "optionIndexes/optionIndex 双缺应为 Required",
        )
        // 双合法 → Ok。
        val ok = okOf("p1", listOf(0, 2))
        assertEquals("p1", ok.pollId)
        assertEquals(listOf(0, 2), ok.optionIndexes)
    }

    @Test
    fun arrayElementValidation() {
        fun of(elements: List<JsonElement>): BotVotePollFieldsResult =
            parseOf(
                JsonObject(
                    mapOf(
                        "pollId" to JsonPrimitive("p1"),
                        "optionIndexes" to JsonArray(elements),
                    )
                )
            )
        // 负数元素 → InvalidOptionIndexes（整体拒绝，不静默截成子集）。
        assertTrue(
            of(listOf(JsonPrimitive(0), JsonPrimitive(-1))) is BotVotePollFieldsResult.InvalidOptionIndexes,
            "负数元素应为 InvalidOptionIndexes",
        )
        // 非整数内容 → InvalidOptionIndexes。
        assertTrue(
            of(listOf(JsonPrimitive("abc"))) is BotVotePollFieldsResult.InvalidOptionIndexes,
            "非整数内容应为 InvalidOptionIndexes",
        )
        // 小数字符串 → InvalidOptionIndexes（toIntOrNull 为 null）。
        assertTrue(
            of(listOf(JsonPrimitive("1.5"))) is BotVotePollFieldsResult.InvalidOptionIndexes,
            "小数字符串应为 InvalidOptionIndexes",
        )
        // JsonNull 元素：JsonPrimitive 字面量 "null" → toIntOrNull 为 null → Invalid。
        assertTrue(
            of(listOf(JsonNull)) is BotVotePollFieldsResult.InvalidOptionIndexes,
            "null 元素应为 InvalidOptionIndexes",
        )
        // 数组嵌套元素 → InvalidOptionIndexes。
        assertTrue(
            of(listOf(JsonArray(listOf(JsonPrimitive(0))))) is BotVotePollFieldsResult.InvalidOptionIndexes,
            "数组型元素应为 InvalidOptionIndexes",
        )
    }

    @Test
    fun singleOptionIndexFallback() {
        fun of(obj: JsonObject): BotVotePollFieldsResult = parseOf(obj)
        // optionIndexes 缺席 → 回退单字段。
        val ok = of(JsonObject(mapOf("pollId" to JsonPrimitive("p1"), "optionIndex" to JsonPrimitive(2))))
        assertTrue(ok is BotVotePollFieldsResult.Ok, "单 optionIndex 合法应为 Ok")
        assertEquals(listOf(2), (ok as BotVotePollFieldsResult.Ok).fields.optionIndexes)
        // 单字段负数 → Required。
        assertTrue(
            of(JsonObject(mapOf("pollId" to JsonPrimitive("p1"), "optionIndex" to JsonPrimitive(-1))))
                is BotVotePollFieldsResult.Required,
            "单 optionIndex 负数应为 Required",
        )
        // 单字段非整数 → Required。
        assertTrue(
            of(JsonObject(mapOf("pollId" to JsonPrimitive("p1"), "optionIndex" to JsonPrimitive("x"))))
                is BotVotePollFieldsResult.Required,
            "单 optionIndex 非整数应为 Required",
        )
    }

    @Test
    fun arrayBeatsSingleIndex() {
        // 两键并存：数组赢，单字段被忽略（即使单字段非法也不 400）。
        val parsed = parseOf(
            JsonObject(
                mapOf(
                    "pollId" to JsonPrimitive("p1"),
                    "optionIndexes" to JsonArray(listOf(JsonPrimitive(3))),
                    "optionIndex" to JsonPrimitive(-99),
                )
            )
        )
        assertTrue(parsed is BotVotePollFieldsResult.Ok, "数组存在时单 optionIndex 应被忽略")
        assertEquals(listOf(3), (parsed as BotVotePollFieldsResult.Ok).fields.optionIndexes)
    }

    @Test
    fun nonArrayOptionIndexesFallsBackToSingle() {
        // optionIndexes 非数组型（as? JsonArray 判 null）→ 回退单字段，不是 400。
        val ok = parseOf(
            JsonObject(
                mapOf(
                    "pollId" to JsonPrimitive("p1"),
                    "optionIndexes" to JsonPrimitive("not-an-array"),
                    "optionIndex" to JsonPrimitive(1),
                )
            )
        )
        assertTrue(ok is BotVotePollFieldsResult.Ok, "字符串型 optionIndexes 应回退单字段")
        assertEquals(listOf(1), (ok as BotVotePollFieldsResult.Ok).fields.optionIndexes)
        // optionIndexes 显式 null → 同样回退；单字段缺席 → Required。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "pollId" to JsonPrimitive("p1"),
                        "optionIndexes" to JsonNull,
                    )
                )
            ) is BotVotePollFieldsResult.Required,
            "null 型 optionIndexes + 单字段缺席应为 Required",
        )
    }

    @Test
    fun pollIdQuirks() {
        // 无 trim：" p1 " 原样保留仍通过必填。
        val untrimmed = okOf(" p1 ", listOf(0))
        assertEquals(" p1 ", untrimmed.pollId)
        // 显式 null：JsonNull 是 JsonPrimitive，content 为字面量 "null" → 非空 → Ok。
        val nulled = parseOf(
            JsonObject(
                mapOf(
                    "pollId" to JsonNull,
                    "optionIndexes" to JsonArray(listOf(JsonPrimitive(0))),
                )
            )
        )
        assertTrue(nulled is BotVotePollFieldsResult.Ok, "pollId 显式 null 应得字面量仍 Ok")
        assertEquals("null", (nulled as BotVotePollFieldsResult.Ok).fields.pollId)
        // 对象/数组型 pollId：在 ?.jsonPrimitive 处大声失败（路由层 StatusPages 映射 400，不是 500）。
        assertFailsWith<IllegalArgumentException>(
            message = "对象型 pollId 应抛 IllegalArgumentException",
        ) {
            parseOf(
                JsonObject(
                    mapOf(
                        "pollId" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                        "optionIndexes" to JsonArray(listOf(JsonPrimitive(0))),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            message = "数组型 pollId 应抛 IllegalArgumentException",
        ) {
            parseOf(
                JsonObject(
                    mapOf(
                        "pollId" to JsonArray(listOf(JsonPrimitive(1))),
                        "optionIndexes" to JsonArray(listOf(JsonPrimitive(0))),
                    )
                )
            )
        }
    }

    @Test
    fun nearMissFieldNamesIgnored() {
        // 近似字段名按未知键忽略：真字段缺席 → Required。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "PollId" to JsonPrimitive("p1"),
                        "poll_id" to JsonPrimitive("p2"),
                        "optionindexes" to JsonArray(listOf(JsonPrimitive(0))),
                        "option_Index" to JsonPrimitive(1),
                    )
                )
            ) is BotVotePollFieldsResult.Required,
            "近似字段名应按未知键忽略、真字段缺席为 Required",
        )
    }
}
