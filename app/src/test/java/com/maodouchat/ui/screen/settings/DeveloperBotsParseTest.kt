package com.maodouchat.ui.screen.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G154：`DeveloperBotsScreen` 里三个 JSON 解析函数的测试。
 *
 * 服务端返回的 bot 列表有三种包装形态（裸数组 / 包在四个键之一 / 单个对象），
 * `tokenOnce` 也有三层嵌套位置。漏一种形态就会让整个机器人页空白。
 */
class DeveloperBotsParseTest {

    // ─── extractBotArray ───

    @Test
    fun `top level array is used directly`() {
        val arr = extractBotArray("""[{"id":"b1"}]""")
        assertEquals(1, arr?.length())
        assertEquals("b1", arr?.getJSONObject(0)?.optString("id"))
    }

    @Test
    fun `array nested under any of the four wrapper keys`() {
        listOf("bots", "data", "items", "content").forEach { key ->
            val arr = extractBotArray("""{"$key":[{"id":"b1"},{"id":"b2"}]}""")
            assertEquals("键 $key 没被识别", 2, arr?.length())
        }
    }

    @Test
    fun `single object with an id is wrapped into a one element array`() {
        val arr = extractBotArray("""{"id":"b1","name":"Bot"}""")
        assertEquals(1, arr?.length())
        assertEquals("b1", arr?.getJSONObject(0)?.optString("id"))
    }

    @Test
    fun `empty and non json return null`() {
        assertNull(extractBotArray(""))
        assertNull(extractBotArray("   "))
        assertNull(extractBotArray("not json"))
    }

    @Test
    fun `object without an id and without a wrapper key returns null`() {
        assertNull(extractBotArray("""{"name":"no id here"}"""))
        assertNull(extractBotArray("""{"other":[{"id":"b1"}]}"""))
    }

    @Test
    fun `wrapper key wins over the single object path`() {
        // 同时有 id 和 bots：先按四个键找，找到就用它（不包装整个外层对象）
        val arr = extractBotArray("""{"id":"outer","bots":[{"id":"inner"}]}""")
        assertEquals(1, arr?.length())
        assertEquals("inner", arr?.getJSONObject(0)?.optString("id"))
    }

    // ─── extractTokenOnce ───

    @Test
    fun `token once at the top level`() {
        assertEquals("t1", extractTokenOnce("""{"tokenOnce":"t1"}"""))
    }

    @Test
    fun `token once falls back through data then bot`() {
        assertEquals("t2", extractTokenOnce("""{"data":{"tokenOnce":"t2"}}"""))
        assertEquals("t3", extractTokenOnce("""{"bot":{"tokenOnce":"t3"}}"""))
    }

    @Test
    fun `top level token once beats the nested ones`() {
        assertEquals(
            "top",
            extractTokenOnce("""{"tokenOnce":"top","data":{"tokenOnce":"nested"},"bot":{"tokenOnce":"nested2"}}""")
        )
    }

    @Test
    fun `token once from the first array element`() {
        assertEquals("t4", extractTokenOnce("""[{"tokenOnce":"t4"},{"tokenOnce":"t5"}]"""))
    }

    @Test
    fun `blank token once does not count`() {
        assertNull(extractTokenOnce("""{"tokenOnce":"   "}"""))
        assertNull(extractTokenOnce("""{"tokenOnce":""}"""))
        assertNull(extractTokenOnce("""{"data":{"tokenOnce":"  "}}"""))
    }

    @Test
    fun `null empty and token free payloads return null`() {
        assertNull(extractTokenOnce(null))
        assertNull(extractTokenOnce(""))
        assertNull(extractTokenOnce("   "))
        assertNull(extractTokenOnce("not json"))
        assertNull(extractTokenOnce("""{"other":"x"}"""))
        assertNull(extractTokenOnce("[]"))
    }

    // ─── parseBots ───

    @Test
    fun `bots are parsed with their fields`() {
        val bots = parseBots(
            """[{"id":"b1","name":"Bot One","username":"bot1","tokenPrefix":"pfx","webhookUrl":"https://h","enabled":false,"tokenOnce":"once"}]"""
        )
        assertEquals(1, bots.size)
        val b = bots.single()
        assertEquals("b1", b.id)
        assertEquals("Bot One", b.name)
        assertEquals("bot1", b.username)
        assertEquals("pfx", b.tokenPrefix)
        assertEquals("https://h", b.webhookUrl)
        assertEquals(false, b.enabled)
        assertEquals("once", b.tokenOnce)
    }

    @Test
    fun `duplicate ids are collapsed keeping the first`() {
        val bots = parseBots(
            """[{"id":"b1","name":"first"},{"id":"b1","name":"second"},{"id":"b2","name":"other"}]"""
        )
        assertEquals(listOf("b1", "b2"), bots.map { it.id })
        assertEquals("first", bots.first().name)
    }

    @Test
    fun `blank name falls back to username then id`() {
        val bots = parseBots(
            """[{"id":"b1","name":"  ","username":"handle"},{"id":"b2","username":"  "},{"id":"b3"}]"""
        )
        assertEquals("handle", bots[0].name)
        assertEquals("b2", bots[1].name)
        assertEquals("b3", bots[2].name)
    }

    @Test
    fun `enabled defaults to true when absent`() {
        val bots = parseBots("""[{"id":"b1"},{"id":"b2","enabled":false}]""")
        assertEquals(true, bots[0].enabled)
        assertEquals(false, bots[1].enabled)
    }

    @Test
    fun `non object elements and blank ids are skipped`() {
        val bots = parseBots("""["str", 42, null, {"id":"  "}, {"id":"ok"}]""")
        assertEquals(listOf("ok"), bots.map { it.id })
    }

    @Test
    fun `empty array and unparseable input yield an empty list`() {
        assertTrue(parseBots("[]").isEmpty())
        assertTrue(parseBots("").isEmpty())
        assertTrue(parseBots("not json").isEmpty())
    }

    @Test
    fun `single object payload parses as one bot`() {
        val bots = parseBots("""{"id":"solo","name":"Solo"}""")
        assertEquals(listOf("solo"), bots.map { it.id })
    }

    @Test
    fun `missing optional fields default to empty strings`() {
        val bots = parseBots("""[{"id":"b1"}]""")
        val b = bots.single()
        assertEquals("", b.username)
        assertEquals("", b.tokenPrefix)
        assertEquals("", b.webhookUrl)
        assertEquals("", b.tokenOnce)
    }
}
