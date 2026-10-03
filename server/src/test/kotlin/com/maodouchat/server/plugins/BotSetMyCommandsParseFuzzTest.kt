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
