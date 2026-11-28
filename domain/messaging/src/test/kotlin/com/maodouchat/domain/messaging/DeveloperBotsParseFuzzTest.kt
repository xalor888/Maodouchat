package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeveloperBotsParsing 三个纯函数的随机 oracle fuzz。
 * oracle 按生产源码重述选择逻辑（解析仍用同一 org.json，共享的只是词法层）；
 * 随机输入固定种子，可复现。任意输入永不抛异常。
 */
class DeveloperBotsParseFuzzTest {

    private val random = Random(2026112202)

    private val wrapperKeys = listOf("bots", "data", "items", "content")

    // ---- extractTokenOnce oracle：按优先级收集候选，取首个非空 ----

    private fun expectedTokenOnce(raw: String?): String? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null
        val candidates = mutableListOf<String>()
        runCatching { JSONObject(text) }.getOrNull()?.let { obj ->
            candidates += obj.optString("tokenOnce")
            obj.optJSONObject("data")?.let { candidates += it.optString("tokenOnce") }
            obj.optJSONObject("bot")?.let { candidates += it.optString("tokenOnce") }
        }
        runCatching { JSONArray(text) }.getOrNull()?.let { arr ->
            arr.optJSONObject(0)?.let { candidates += it.optString("tokenOnce") }
        }
        return candidates.firstOrNull { it.isNotBlank() }
    }

    // ---- extractBotArray oracle：裸数组 / 四包装键按序 / 含 id 单对象 ----

    private fun expectedBotArray(raw: String): JSONArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONArray(trimmed) }.getOrNull()?.let { return it }
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val wrapped = wrapperKeys.firstNotNullOfOrNull { obj.optJSONArray(it) }
        if (wrapped != null) return wrapped
        return if (obj.has("id")) JSONArray().put(obj) else null
    }

    // ---- parseBots oracle：去重 + 名称回退 + enabled 缺省 true ----

    private fun expectedParseBots(raw: String): List<BotUi> {
        val arr = expectedBotArray(raw) ?: return emptyList()
        val seen = HashSet<String>()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isBlank() || !seen.add(id)) continue
                val username = o.optString("username").trim()
                add(
                    BotUi(
                        id = id,
                        name = o.optString("name").trim().ifBlank { username.ifBlank { id } },
                        username = username,
                        tokenPrefix = o.optString("tokenPrefix"),
                        webhookUrl = o.optString("webhookUrl"),
                        enabled = o.optBoolean("enabled", true),
                        tokenOnce = o.optString("tokenOnce")
                    )
                )
            }
        }
    }

    // ---- 随机输入：用 org.json 构造合法结构 + 随机垃圾/截断 ----

    private fun randomWord(): String {
        val words = listOf("", " ", "alice", "a b", "tok-1_2", "Bot", "x".repeat(50), "  pad  ")
        return words.random(random)
    }

    private fun randomId(): String {
        val alphabet = "abcXYZ019_-"
        return buildString {
            repeat(random.nextInt(1, 7)) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun randomBotObject(): JSONObject {
        val obj = JSONObject()
        when (random.nextInt(0, 10)) {
            0 -> Unit // id 缺失
            1 -> obj.put("id", "  ")
            2 -> obj.put("id", 42)
            3 -> obj.put("id", JSONObject.NULL)
            else -> obj.put("id", randomId())
        }
        if (random.nextBoolean()) obj.put("name", randomWord())
        if (random.nextBoolean()) obj.put("username", randomWord())
        if (random.nextBoolean()) obj.put("tokenPrefix", randomWord())
        if (random.nextBoolean()) obj.put("webhookUrl", randomWord())
        if (random.nextBoolean()) obj.put("enabled", random.nextBoolean())
        if (random.nextBoolean()) obj.put("enabled", if (random.nextBoolean()) "true" else "false")
        if (random.nextBoolean()) obj.put("tokenOnce", randomWord())
        return obj
    }

    private fun randomBotArray(): JSONArray {
        val arr = JSONArray()
        repeat(random.nextInt(0, 4)) {
            when (random.nextInt(0, 6)) {
                0 -> arr.put(1)
                1 -> arr.put("x")
                2 -> arr.put(JSONObject.NULL)
                else -> arr.put(randomBotObject())
            }
        }
        return arr
    }

    private fun randomRaw(): String {
        return when (random.nextInt(0, 6)) {
            0 -> randomBotArray().toString()
            1 -> {
                val key = wrapperKeys.random(random)
                JSONObject().apply {
                    put(key, randomBotArray())
                    if (random.nextBoolean()) put("tokenOnce", randomWord())
                    if (key != "data" && random.nextBoolean()) {
                        put("data", JSONObject().put("tokenOnce", randomWord()))
                    }
                    if (random.nextBoolean()) put("bot", JSONObject().put("tokenOnce", randomWord()))
                }.toString()
            }
            2 -> randomBotObject().toString()
            3 -> JSONObject().put("tokenOnce", randomWord()).toString()
            4 -> JSONArray().put(JSONObject().put("tokenOnce", randomWord())).toString()
            else -> randomGarbage()
        }
    }

    private fun randomGarbage(): String {
        val alphabet = "abc{}[]\",:012 truefalsn"
        return buildString {
            repeat(random.nextInt(0, 30)) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    @Test
    fun extractTokenOnceMatchesOracle() {
        repeat(300) {
            val raw = randomRaw()
            assertEquals(expectedTokenOnce(raw), extractTokenOnce(raw))
        }
    }

    @Test
    fun extractBotArrayMatchesOracle() {
        repeat(300) {
            val raw = randomRaw()
            val expected = expectedBotArray(raw)
            val actual = extractBotArray(raw)
            assertEquals(expected?.length(), actual?.length())
            assertEquals(expected?.toString(), actual?.toString())
        }
    }

    @Test
    fun parseBotsMatchesOracle() {
        repeat(300) {
            val raw = randomRaw()
            assertEquals(expectedParseBots(raw), parseBots(raw))
        }
    }

    @Test
    fun wrapperKeyOrderAndSingleObjectPinned() {
        // 四包装键按 bots → data → items → content 顺序，首个为数组的获胜。
        assertEquals(
            1,
            extractBotArray("""{"bots":[{"id":"b1"}],"data":[{"id":"d1"},{"id":"d2"}]}""")?.length()
        )
        assertEquals(
            2,
            extractBotArray("""{"data":[{"id":"d1"},{"id":"d2"}],"items":[{"id":"i1"}]}""")?.length()
        )
        assertEquals(
            1,
            extractBotArray("""{"items":[{"id":"i1"}],"content":[{"id":"c1"},{"id":"c2"}]}""")?.length()
        )
        assertEquals(
            2,
            extractBotArray("""{"content":[{"id":"c1"},{"id":"c2"}]}""")?.length()
        )
        // 非数组的包装值跳过，继续找下一键。
        assertEquals(
            1,
            extractBotArray("""{"bots":"not-an-array","data":[{"id":"d1"}]}""")?.length()
        )
        assertEquals(
            1,
            extractBotArray("""{"bots":null,"data":[{"id":"d1"}]}""")?.length()
        )
        // 含 id 的单对象包成单元素数组；无 id 无包装键返回 null。
        assertEquals(1, extractBotArray("""{"id":"b1","name":"Bot"}""")?.length())
        assertNull(extractBotArray("""{"foo":1}"""))
        assertNull(extractBotArray(""))
        assertNull(extractBotArray("not json"))
        // 空数组是合法结果，不是 null。
        assertEquals(0, extractBotArray("[]")?.length())
        assertEquals(0, extractBotArray("""{"bots":[]}""")?.length())
        assertEquals(emptyList(), parseBots("[]"))
        // id 为 null 仍算“有 id”而被包装，但解析时 id 为空串被跳过。
        assertEquals(emptyList(), parseBots("""{"id":null}"""))
    }

    @Test
    fun nameFallbackDedupAndEnabledPinned() {
        assertEquals("alice", parseBots("""[{"id":"b1","name":"","username":"alice"}]""").single().name)
        assertEquals("b1", parseBots("""[{"id":"b1","name":"  ","username":""}]""").single().name)
        assertEquals("alice", parseBots("""[{"id":"b1","username":"alice"}]""").single().name)
        assertEquals("b1", parseBots("""[{"id":" b1 ","name":"N"}]""").single().id)
        // 去重：trim 后相同的 id 只留首个；大小写敏感。
        assertEquals(1, parseBots("""[{"id":"b1"},{"id":"b1","name":"Second"}]""").size)
        assertEquals("b1", parseBots("""[{"id":"b1"},{"id":"b1","name":"Second"}]""").single().name)
        assertEquals(1, parseBots("""[{"id":"b1"},{"id":"b1 "}]""").size)
        assertEquals(2, parseBots("""[{"id":"b1"},{"id":"B1"}]""").size)
        // enabled 缺省 true；字符串 "true"/"false" 也解析；其它值回缺省。
        assertEquals(false, parseBots("""[{"id":"b1","enabled":false}]""").single().enabled)
        assertEquals(true, parseBots("""[{"id":"b1"}]""").single().enabled)
        assertEquals(false, parseBots("""[{"id":"b1","enabled":"false"}]""").single().enabled)
        assertEquals(true, parseBots("""[{"id":"b1","enabled":"yes"}]""").single().enabled)
        assertEquals(true, parseBots("""[{"id":"b1","enabled":0}]""").single().enabled)
        // 数字 id 走 optString 的 toString；缺失字段为空串。
        assertEquals("42", parseBots("""[{"id":42,"name":"N"}]""").single().id)
        assertEquals("", parseBots("""[{"id":"b1"}]""").single().tokenOnce)
        assertEquals("tok", parseBots("""[{"id":"b1","tokenOnce":"tok"}]""").single().tokenOnce)
        // 非对象元素跳过。
        assertEquals(1, parseBots("""[1,"x",{"id":"b1"},null]""").size)
    }

    @Test
    fun garbageNeverThrowsAndInvariantsHold() {
        repeat(300) {
            val raw = randomGarbage()
            val bots = parseBots(raw)
            assertTrue(bots.all { it.id.isNotBlank() })
            assertEquals(bots.map { it.id }.toSet().size, bots.size)
            assertTrue(bots.all { it.name.isNotBlank() })
            extractTokenOnce(raw)?.let { assertTrue(it.isNotBlank()) }
            extractBotArray(raw)?.let { assertTrue(it.length() >= 0) }
        }
        // 截断的合法 JSON 也不抛异常。
        val valid = """{"bots":[{"id":"b1","name":"Bot"}],"tokenOnce":"tok"}"""
        for (cut in 1 until valid.length) {
            parseBots(valid.substring(0, cut))
            extractTokenOnce(valid.substring(0, cut))
            extractBotArray(valid.substring(0, cut))
        }
    }
}
