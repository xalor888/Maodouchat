package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
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
 * Bot `setMyCommands` 请求体手写解析的**模糊兼容性测试**
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 起至 `setChatPermissions` 之后第六十六块）。
 *
 * 本测试直接钉住纯函数 ([parseBotSetMyCommandsFields]) 的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。已知字段为顶层 `commands` 与项内 `command`/`description`。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 吸取第四十一块（`sendTable`）的 CI 教训：fuzz 基 payload 的**必填字段必须
 *   确定性合法**——本端点唯一必填是 `commands` 数组，基 payload 恒带一个合法项
 *   （`command`=`"c<i>"`、`description`=`"d<i>"`，均非空），`okOf` 永不抛
 *   `ClassCastException`。
 * - 钉住 **`commands` 安全转型**：缺席 / 对象 / 字符串 / 数字 / 显式 null 一律
 *   `Invalid`（400 `"commands array required"`），**不抛**——与 `?.jsonPrimitive`
 *   的大声失败是两套判据，不可混淆。
 * - 钉住 **项级静默丢弃**：非对象项直接丢弃；`command`/`description` 缺 / 空 /
 *   纯空白的项丢弃；全丢弃或空数组 → `Ok(emptyList())`（不是 `Invalid`）。
 * - 钉住 **显式 null 得字面量 `"null"`**：`JsonNull` 是 `JsonPrimitive`，
 *   `command`=显式 null → `"null"` 非空→保留（原处理器逐字怪语义）。
 * - 钉住 **项内大声失败**：`command`/`description` 为对象 / 数组型时，
 *   `?.jsonPrimitive` 抛 [IllegalArgumentException]（路由层 `StatusPages`
 *   映射为 400「参数无效」，不是 500）——注意这与顶层的 `as?` 安全转型不同。
 * - 钉住 **近似字段名按未知键忽略**：`Commands`（首字母大写）≠ `commands` → `Invalid`。
 *
 * 本轮测试代码延续规避字符串模板内嵌套引号写法（消息文案用 `+` 拼接）。
 */
class BotSetMyCommandsParseFuzzTest {

    private companion object {
        /** 解析涉及的全部已知字段名：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf("commands", "command", "description")
        private const val ITERATIONS = 150
    }

    private fun parseOf(obj: JsonObject): BotSetMyCommandsFieldsResult =
        parseBotSetMyCommandsFields(obj)

    private fun okOf(obj: JsonObject): List<BotRepository.BotCommandDef> =
        (parseOf(obj) as BotSetMyCommandsFieldsResult.Ok).fields.commands

    private fun itemOf(command: String, description: String): JsonObject =
        JsonObject(mapOf("command" to JsonPrimitive(command), "description" to JsonPrimitive(description)))

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
        val random = Random(2026100266)
        repeat(ITERATIONS) { i ->
            val base = mutableMapOf<String, JsonElement>()
            // 必填字段确定性合法（第四十一块 CI 教训）：commands 恒为单元素合法数组。
            base["commands"] = JsonArray(listOf(itemOf("c" + i, "d" + i)))
            // 程序化注入未知字段：永不破坏 JSON 语法。
            repeat(random.nextInt(0, 6)) {
                base[randomName(random)] = randomScalar(random)
            }
            val defs = okOf(JsonObject(base))
            assertEquals(1, defs.size, "未知键不得增删命令项，迭代 " + i)
            assertEquals("c" + i, defs[0].command, "未知键不得污染 command，迭代 " + i)
            assertEquals("d" + i, defs[0].description, "未知键不得污染 description，迭代 " + i)
        }
    }

    @Test
    fun commandsArrayRequired() {
        // commands 缺席 → Invalid。
        assertTrue(parseOf(JsonObject(emptyMap())) is BotSetMyCommandsFieldsResult.Invalid)
        // commands 非数组型 → Invalid（安全转型，不抛）：对象 / 字符串 / 数字 / 显式 null。
        assertTrue(
            parseOf(JsonObject(mapOf("commands" to JsonObject(emptyMap()))))
                is BotSetMyCommandsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("commands" to JsonPrimitive("x"))))
                is BotSetMyCommandsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("commands" to JsonPrimitive(1))))
                is BotSetMyCommandsFieldsResult.Invalid
        )
        assertTrue(
            parseOf(JsonObject(mapOf("commands" to JsonNull)))
                is BotSetMyCommandsFieldsResult.Invalid
        )
        // 空数组 → Ok(emptyList())，不是 Invalid（下游 normalizeCommands 照常判定）。
        assertEquals(
            emptyList(),
            okOf(JsonObject(mapOf("commands" to JsonArray(emptyList()))))
        )
    }

    @Test
    fun itemFiltering() {
        // 非对象项静默丢弃：字符串 / 数字 / null / 数组项都不进 400。
        val defs = okOf(
            JsonObject(
                mapOf(
                    "commands" to JsonArray(
                        listOf(
                            JsonPrimitive("x"),
                            JsonPrimitive(1),
                            JsonNull,
                            JsonArray(emptyList()),
                            itemOf("keep", "留")
                        )
                    )
                )
            )
        )
        assertEquals(1, defs.size, "非对象项必须静默丢弃")
        assertEquals("keep", defs[0].command)
        // command/description 缺 / 空 / 纯空白的项丢弃。
        val defs2 = okOf(
            JsonObject(
                mapOf(
                    "commands" to JsonArray(
                        listOf(
                            JsonObject(mapOf("description" to JsonPrimitive("d"))),
                            JsonObject(mapOf("command" to JsonPrimitive("c"))),
                            JsonObject(
                                mapOf(
                                    "command" to JsonPrimitive(""),
                                    "description" to JsonPrimitive("d")
                                )
                            ),
                            JsonObject(
                                mapOf(
                                    "command" to JsonPrimitive("   "),
                                    "description" to JsonPrimitive("d")
                                )
                            ),
                            itemOf("ok", "好")
                        )
                    )
                )
            )
        )
        assertEquals(listOf(BotRepository.BotCommandDef("ok", "好")), defs2)
        // 全丢弃 → Ok(emptyList())，不是 Invalid。
        assertEquals(
            emptyList(),
            okOf(
                JsonObject(
                    mapOf(
                        "commands" to JsonArray(listOf(JsonObject(emptyMap())))
                    )
                )
            )
        )
    }

    @Test
    fun explicitNullBecomesLiteralNull() {
        // 显式 null 的 command 得字面量 "null"→非空→保留（原处理器逐字怪语义）。
        val defs = okOf(
            JsonObject(
                mapOf(
                    "commands" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "command" to JsonNull,
                                    "description" to JsonPrimitive("d")
                                )
                            )
                        )
                    )
                )
            )
        )
        assertEquals(listOf(BotRepository.BotCommandDef("null", "d")), defs)
    }

    @Test
    fun loudFailureInsideItems() {
        // 项内 command 对象型 → ?.jsonPrimitive 大声失败（与顶层 as? 安全转型不同）。
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "commands" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "command" to JsonObject(mapOf("x" to JsonPrimitive(1))),
                                        "description" to JsonPrimitive("d")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }
        // 项内 description 数组型 → 同样大声失败。
        assertFailsWith<IllegalArgumentException> {
            parseOf(
                JsonObject(
                    mapOf(
                        "commands" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "command" to JsonPrimitive("c"),
                                        "description" to JsonArray(emptyList())
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun approximateFieldNamesIgnored() {
        // 近似字段名按未知键忽略：真字段缺席 → Invalid。
        assertTrue(
            parseOf(
                JsonObject(
                    mapOf(
                        "Commands" to JsonArray(listOf(itemOf("c1", "d1")))
                    )
                )
            ) is BotSetMyCommandsFieldsResult.Invalid
        )
    }
}
