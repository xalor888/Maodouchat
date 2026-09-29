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
 * Bot `sendLocation` 请求体手写解析的**模糊兼容性测试**（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo` 之后第七块）。
 *
 * Bot 路由没有 typed DTO——请求体是 `receiveBoundedTextOrEmpty()` 拿到的原始字符串，
 * 经 `Json.parseToJsonElement(body).jsonObject` 再手写抽取。本轮把原来内联在
 * `/api/bot/sendLocation` 处理器里的抽取 / 校验 / 内容组装逻辑收敛为纯函数
 * ([parseBotSendLocationFields] / [buildBotLocationContent])
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
 *   `.content` 为 `"null"` 字符串，`toDoubleOrNull()` 得 null）——对坐标字段而言，
 *   主字段非数字时**穿透到别名**继续找（`?:` 接在 `toDoubleOrNull()` 之后，
 *   原处理器逐字语义），整条链都取不到数字才 `MissingRequired`，特意钉住
 *   （上一轮 sendVideo 的 duration 显式 null 回 0 是同一族语义）。
 * - 钉住别名优先级（`latitude`→`lat`、`longitude`→`lng`→`lon`）、缺省值
 *   （title 缺省→`""`）、title 80 截断、坐标范围（纬度 ±90、经度 ±180，
 *   `invalid coordinates`）、内容模板形状（`"📍 "` + 可选 title + `%.6f` + `[location:]` 行）。
 */
class BotSendLocationParseFuzzTest {

    private companion object {
        /** sendLocation 解析涉及的全部已知字段名（含别名）：随机名必须避开它们。 */
        private val KNOWN_FIELD_NAMES = setOf(
            "chatId", "title",
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

    private fun fieldsOf(obj: JsonObject): BotSendLocationFields {
        val result = parseBotSendLocationFields(obj)
        assertTrue(result is BotSendLocationFieldsResult.Ok, "合法请求必须解析成功，实际 $result")
        return result.fields
    }

    @Test
    fun `sendLocation parse survives seeded unknown-field fuzz`() {
        val random = Random(0x10CA_7109)
        repeat(ITERATIONS) { i ->
            val chatId = "c-$i"
            val title = "title-$i"
            val lat = -90.0 + (i % 18000) / 100.0 // [-90, 90)
            val lon = -180.0 + (i % 36000) / 100.0 // [-180, 180)
            val base = JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive(chatId),
                    "title" to JsonPrimitive(title),
                    "latitude" to JsonPrimitive(lat),
                    "longitude" to JsonPrimitive(lon),
                )
            )
            val obj = injectUnknownFields(base, random)
            val fields = fieldsOf(obj)
            assertEquals(
                BotSendLocationFields(chatId, lat, lon, title),
                fields,
                "fuzz 迭代 #$i：已知字段必须与注入前完全一致，未知键必须被忽略",
            )
        }
    }

    @Test
    fun `sendLocation required fields are enforced`() {
        // chatId 缺失 → MissingRequired
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(mapOf("latitude" to JsonPrimitive(1.0), "longitude" to JsonPrimitive(2.0)))
            ) is BotSendLocationFieldsResult.MissingRequired,
            "chatId 缺失必须判缺",
        )
        // 纬度缺失 → MissingRequired
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "longitude" to JsonPrimitive(2.0)))
            ) is BotSendLocationFieldsResult.MissingRequired,
            "纬度缺失必须判缺",
        )
        // 经度缺失 → MissingRequired
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(mapOf("chatId" to JsonPrimitive("c"), "latitude" to JsonPrimitive(1.0)))
            ) is BotSendLocationFieldsResult.MissingRequired,
            "经度缺失必须判缺",
        )
        // 空白 chatId → MissingRequired
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("  "),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                    )
                )
            ) is BotSendLocationFieldsResult.MissingRequired,
            "空白 chatId 必须判缺",
        )
    }

    @Test
    fun `sendLocation alias priority is pinned`() {
        // latitude → lat
        val latAlias = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(10.0),
                    "lat" to JsonPrimitive(20.0),
                    "longitude" to JsonPrimitive(30.0),
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
                )
            )
        )
        assertEquals(20.0, approx.latitude, "近似字段名 Latitude 必须被忽略")
    }

    @Test
    fun `sendLocation non-numeric primary falls through to alias`() {
        // 「怪」语义钉住：?: 接在 toDoubleOrNull() 之后，主字段非数字时穿透到别名，
        // 而不是直接判缺——与原内联处理器逐字一致
        val fallthrough = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive("not-a-number"),
                    "lat" to JsonPrimitive(1.5),
                    "longitude" to JsonPrimitive(2.5),
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
                )
            )
        )
        assertEquals(3.5, nullPrimary.latitude, "null latitude 必须穿透到 lat 别名")
        // 整条链都取不到数字 → MissingRequired（不是大声失败）
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive("abc"),
                        "longitude" to JsonPrimitive(2.5),
                    )
                )
            ) is BotSendLocationFieldsResult.MissingRequired,
            "latitude 全链非数字必须判缺（chatId/latitude/longitude required）",
        )
        // 经度链同样：longitude 非数字 → lng 为 null → lon 生效
        val lonChain = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive("xyz"),
                    "lng" to JsonNull,
                    "lon" to JsonPrimitive(2.5),
                )
            )
        )
        assertEquals(2.5, lonChain.longitude, "longitude→lng→lon 穿透链必须逐字保留")
    }

    @Test
    fun `sendLocation wrong-typed known fields fail loudly`() {
        // 已知字段类型错 → IllegalArgumentException（路由层 StatusPages 映射 400，不是 500）
        assertFailsWith<IllegalArgumentException>(
            "chatId 收对象必须大声失败",
        ) {
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonObject(mapOf("id" to JsonPrimitive("c"))),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "latitude 收数组必须大声失败",
        ) {
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonArray(listOf(JsonPrimitive(1.0))),
                        "longitude" to JsonPrimitive(2.0),
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException>(
            "title 收对象必须大声失败（不是静默回空串）",
        ) {
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(1.0),
                        "longitude" to JsonPrimitive(2.0),
                        "title" to JsonObject(mapOf("t" to JsonPrimitive("x"))),
                    )
                )
            )
        }
    }

    @Test
    fun `sendLocation title default and truncation are pinned`() {
        // title 缺省 → ""（不是 null）
        val noTitle = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive(2.0),
                )
            )
        )
        assertEquals("", noTitle.title, "title 缺省必须回空串")
        // title 80 截断
        val long = "x".repeat(100)
        val truncated = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(1.0),
                    "longitude" to JsonPrimitive(2.0),
                    "title" to JsonPrimitive(long),
                )
            )
        )
        assertEquals(80, truncated.title.length, "title 必须截断到 80")
        assertEquals("x".repeat(80), truncated.title)
    }

    @Test
    fun `sendLocation coordinate range is enforced`() {
        // 纬度越界 → InvalidCoordinates（invalid coordinates，400）
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(90.000001),
                        "longitude" to JsonPrimitive(0.0),
                    )
                )
            ) is BotSendLocationFieldsResult.InvalidCoordinates,
            "纬度 > 90 必须判坐标非法",
        )
        assertTrue(
            parseBotSendLocationFields(
                JsonObject(
                    mapOf(
                        "chatId" to JsonPrimitive("c"),
                        "latitude" to JsonPrimitive(0.0),
                        "longitude" to JsonPrimitive(-180.000001),
                    )
                )
            ) is BotSendLocationFieldsResult.InvalidCoordinates,
            "经度 < -180 必须判坐标非法",
        )
        // 边界值合法（含端点）
        val edges = fieldsOf(
            JsonObject(
                mapOf(
                    "chatId" to JsonPrimitive("c"),
                    "latitude" to JsonPrimitive(-90.0),
                    "longitude" to JsonPrimitive(180.0),
                )
            )
        )
        assertEquals(-90.0, edges.latitude)
        assertEquals(180.0, edges.longitude)
    }

    @Test
    fun `sendLocation content shape is pinned`() {
        // 有 title 的完整形状（%.6f 格式化 + [location:] 行用 Double 原样拼）
        assertEquals(
            "📍 home 12.345679,98.765432\n[location:12.3456789,98.7654321]",
            buildBotLocationContent(12.3456789, 98.7654321, "home"),
            "完整模板形状必须钉住",
        )
        // 空白 title → 不拼 title 段
        assertEquals(
            "📍 -90.000000,180.000000\n[location:-90.0,180.0]",
            buildBotLocationContent(-90.0, 180.0, "   "),
            "空白 title 必须不拼段",
        )
    }
}
