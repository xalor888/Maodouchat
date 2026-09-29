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
 * Bot `sendVenue` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、
 * `sendContact` 之后第十块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendVenue` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendVenueFields] / [buildBotVenueContent])
 * （生产侧零行为改动：校验顺序仍为 必填 → 坐标范围（纯函数）→ 成员检查（处理器）），
 * 本测试直接钉住这些函数的生产语义：
 *
 * - **固定种子** [Random]：CI 上确定性可复跑，150 个随机 payload。
 * - **随机名避开所有已知字段**（见 [KNOWN_FIELD_NAMES]）：否则测的是「重复键覆盖语义」，
 *   而不是「未知键忽略语义」。
 * - **随机值**覆盖布尔 / 整数 / 浮点 / 字符串 / null / 数组 / 嵌套对象（深度 ≤ 2），
 *   注入位置为顶层。
 * - payload 由「合法请求先构造成 JsonObject，再程序化注入未知字段」得到（不拼字符串）——
 *   注入本身永不破坏 JSON 语法，红只可能来自解析侧。
 * - 反证 `wrong-typed known fields still fail loudly`：手写解析里 `?.jsonPrimitive`
 *   在类型错时抛 [IllegalArgumentException]，路由层 `StatusPages` 把它映射为 400
 *   「参数无效」（不是 500）——坏数据必须大声失败，不能悄悄吞掉。
 *   例外：显式 JSON null 不是类型错（`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   `.content` 为 `"null"` 字符串）——对坐标字段而言，主字段非数字时**穿透到别名**继续找
 *   （`?:` 接在 `toDoubleOrNull()` 之后，原处理器逐字语义），整条链都取不到数字才
 *   `MissingRequired`，特意钉住。
 * - 钉住别名优先级（`latitude`→`lat`、`longitude`→`lng`→`lon`）、缺省值
 *   （title 缺省→`""`、address 缺省→`""`）、title `trim()` + 80 截断、address 160 截断、
 *   坐标范围（纬度 ±90、经度 ±180，`invalid coordinates`）、内容模板形状
 *   （`"📌 "` + title + 可选 `"\n"` + address + `"\n"` + `%.6f` + `"\n[venue:lat,lon|title]"` 行，
 *   标记行 lat/lon 是 Double 原始 toString，title 竖线转斜线）。
 */
class BotSendVenueParseFuzzTest {

    private companion object {
        /** sendVenue 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "title", "address",
            "latitude", "lat",
            "longitude", "lng", "lon",
        )
        private const val ITERATIONS = 150
    }

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "clientExt", "appExt", "exp", "flag",
        )
        var name = "${stems.random(random)}_${random.nextInt(10000)}"
        while (name in KNOWN_FIELD_NAMES) name = "z$name"
        return name
    }

    private fun randomJsonValue(random: Random, depth: Int): JsonElement {
        val leafKinds = 5 // bool / long / double / string / null
        return when (random.nextInt(if (depth <= 0) leafKinds else leafKinds + 2)) {
            0 -> JsonPrimitive(random.nextBoolean())
            1 -> JsonPrimitive(random.nextLong())
            2 -> JsonPrimitive(random.nextDouble())
            3 -> JsonPrimitive("str_${random.nextInt(100000)}_${random.nextLong()}")
            4 -> JsonNull
            5 -> JsonArray(List(random.nextInt(1, 4)) { randomJsonValue(random, depth - 1) })
            else -> JsonObject(
                (0 until random.nextInt(1, 4))
                    .associate { randomFieldName(random) to randomJsonValue(random, depth - 1) }
            )
        }
    }

    /** 顶层注入 1–5 个未知字段。 */
    private fun injectUnknownFields(base: JsonObject, random: Random): JsonObject {
        val fields = base.toMutableMap()
        repeat(random.nextInt(1, 6)) {
            fields[randomFieldName(random)] = randomJsonValue(random, 2)
        }
        return JsonObject(fields)
    }

    private fun fieldsOf(obj: JsonObject): BotSendVenueFields {
        val result = parseBotSendVenueFields(obj)
        assertTrue(result is BotSendVenueFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendVenue parse survives seeded unknown-field fuzz`() {
        val random = Random(0x1E_70A1)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val title = "title-$i"
            val address = "addr-$i"
            val lat = -90.0 + (i % 18000) / 100.0 // [-90, 90)
            val lon = -180.0 + (i % 36000) / 100.0 // [-180, 180)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "title" to JsonPrimitive(title),
                    "address" to JsonPrimitive(address),
                    "latitude" to JsonPrimitive(lat),
                    "longitude" to JsonPrimitive(lon),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendVenueFields(chatId, lat, lon, title, address),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendVenue required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendVenueFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // 纬度缺失 → MissingRequired
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendVenueFieldsResult.MissingRequired,
            "纬度缺失必须判缺",
        )
        // 经度缺失 → MissingRequired
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendVenueFieldsResult.MissingRequired,
            "经度缺失必须判缺",
        )
        // title 缺失 → MissingRequired（与 sendLocation 不同：venue 的 title 是必填）
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                    )
                )
            ) is BotSendVenueFieldsResult.MissingRequired,
            "title 缺失必须判缺",
        )
        // 空白 title → MissingRequired
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("   "),
                    )
                )
            ) is BotSendVenueFieldsResult.MissingRequired,
            "空白 title 必须判缺",
        )
        // address 缺省不判缺（→ ""）
        val noAddress = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive(2.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals("", noAddress.address, "address 缺省必须回空串")
    }

    @Test
    fun `sendVenue alias priority is pinned`() {
        // latitude → lat
        val latAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(10.0),
                    "lat" to JsonPrimitive(20.0),
                    "longitude" to JsonPrimitive(30.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(10.0, latAlias.latitude, "latitude 必须优先于 lat")
        val latOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "lat" to JsonPrimitive(20.0),
                    "longitude" to JsonPrimitive(30.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(20.0, latOnly.latitude, "lat 别名必须被解析")
        // longitude → lng → lon
        val lonAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(10.0),
                    "longitude" to JsonPrimitive(30.0),
                    "lng" to JsonPrimitive(40.0),
                    "lon" to JsonPrimitive(50.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(30.0, lonAlias.longitude, "longitude 必须优先于 lng/lon")
        val lngOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(10.0),
                    "lng" to JsonPrimitive(40.0),
                    "lon" to JsonPrimitive(50.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(40.0, lngOnly.longitude, "lng 必须优先于 lon")
        val lonOnly = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(10.0),
                    "lon" to JsonPrimitive(50.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(50.0, lonOnly.longitude, "lon 别名必须被解析")
        // 近似字段名被忽略（不是别名）
        val approx = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "Latitude" to JsonPrimitive(99.0),
                    "lat" to JsonPrimitive(20.0),
                    "longitude" to JsonPrimitive(30.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(20.0, approx.latitude, "近似字段名 Latitude 必须被忽略")
    }

    @Test
    fun `sendVenue non-numeric primary falls through to alias`() {
        // 「怪」语义钉住：?: 接在 toDoubleOrNull() 之后，主字段非数字时穿透到别名，
        // 而不是直接判缺——与原内联处理器逐字一致
        val fallthrough = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive("not-a-number"),
                    "lat" to JsonPrimitive(1.5),
                    "longitude" to JsonPrimitive(2.5),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(1.5, fallthrough.latitude, "非数字 latitude 必须穿透到 lat 别名")
        // 显式 JSON null 同样穿透（JsonNull.content 为 "null" 字符串，非数字）
        val nullPrimary = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonNull,
                    "lat" to JsonPrimitive(3.5),
                    "longitude" to JsonPrimitive(4.5),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(3.5, nullPrimary.latitude, "null latitude 必须穿透到 lat 别名")
        // 整条链都取不到数字 → MissingRequired
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonNull,
                        "longitude" to JsonPrimitive("bad"),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendVenueFieldsResult.MissingRequired,
            "整条别名链都取不到数字必须判缺",
        )
    }

    @Test
    fun `sendVenue wrong-typed known fields fail loudly`() {
        // 对象型 chatId → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "对象型 chatId 必须大声失败"
        ) {
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("t"),
                    )
                )
            )
        }
        // 数组型 latitude → IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "数组型 latitude 必须大声失败"
        ) {
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonArray(listOf(JsonPrimitive(1.0))),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("t"),
                    )
                )
            )
        }
        // 对象型 title → IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "对象型 title 必须大声失败"
        ) {
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonObject(mapOf("nested" to JsonPrimitive(1))),
                    )
                )
            )
        }
        // 数组型 address → IllegalArgumentException
        assertFailsWith<IllegalArgumentException>(
            "数组型 address 必须大声失败"
        ) {
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("t"),
                        "address" to JsonArray(listOf(JsonPrimitive("a"))),
                    )
                )
            )
        }
    }

    @Test
    fun `sendVenue title and address trimming and truncation are pinned`() {
        // title 有 trim()（原处理器逐字语义），address 同样
        val trimmed = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive(2.0),
                    "title" to JsonPrimitive("  hall  "),
                    "address" to JsonPrimitive("\troad\n"),
                )
            )
        )
        assertEquals("hall", trimmed.title, "title 必须 trim")
        assertEquals("road", trimmed.address, "address 必须 trim")
        // title 80 截断（先 trim 再 take）
        val longTitle = "t".repeat(100)
        val truncatedTitle = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive(2.0),
                    "title" to JsonPrimitive(longTitle),
                )
            )
        )
        assertEquals("t".repeat(80), truncatedTitle.title, "title 必须 80 截断")
        // address 160 截断
        val truncatedAddress = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive(2.0),
                    "title" to JsonPrimitive("t"),
                    "address" to JsonPrimitive("a".repeat(200)),
                )
            )
        )
        assertEquals("a".repeat(160), truncatedAddress.address, "address 必须 160 截断")
    }

    @Test
    fun `sendVenue coordinate range is enforced`() {
        // 纬度越界 → InvalidCoordinates
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(90.1),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendVenueFieldsResult.InvalidCoordinates,
            "纬度 90.1 必须判坐标非法",
        )
        // 经度越界 → InvalidCoordinates
        assertTrue(
            parseBotSendVenueFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(180.1),
                        "title" to JsonPrimitive("t"),
                    )
                )
            ) is BotSendVenueFieldsResult.InvalidCoordinates,
            "经度 180.1 必须判坐标非法",
        )
        // 边界值合法
        val edge = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(-90.0),
                    "longitude" to JsonPrimitive(180.0),
                    "title" to JsonPrimitive("t"),
                )
            )
        )
        assertEquals(-90.0, edge.latitude)
        assertEquals(180.0, edge.longitude)
    }

    @Test
    fun `sendVenue content template shape is pinned`() {
        // 带 address 的完整模板
        val full = buildBotVenueContent(31.2304, 121.4737, "hall", "road 1")
        assertEquals(
            "📌 hall\nroad 1\n31.230400,121.473700\n[venue:31.2304,121.4737|hall]",
            full,
            "完整内容模板必须逐字一致",
        )
        // address 为空时不补第二行；title 竖线转斜线（标记行 lat/lon 是原始 Double toString）
        val noAddress = buildBotVenueContent(1.0, 2.5, "a|b", "")
        assertEquals(
            "📌 a|b\n1.000000,2.500000\n[venue:1.0,2.5|a/b]",
            noAddress,
            "address 为空时不补行、title 竖线转斜线必须逐字一致",
        )
    }
}
