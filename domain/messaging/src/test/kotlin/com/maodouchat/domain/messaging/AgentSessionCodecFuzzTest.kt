package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSessionCodecFuzzTest {

    private companion object {
        private const val GARBAGE_ITERATIONS = 200
        private const val ROUND_TRIP_ITERATIONS = 150
        private val TEXT_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,;:!?-_()[]'\"/\\@#%&=+*~，。；：、"
        private val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private val GARBAGE_ALPHABET = "abcxyz012{}[]\",: truefalsn|#@"
        private val ROLES = listOf("user", "assistant", "tool", "system")
    }

    private fun randomText(random: Random, maxLen: Int = 24): String {
        val len = random.nextInt(0, maxLen + 1)
        return buildString { repeat(len) { append(TEXT_ALPHABET[random.nextInt(TEXT_ALPHABET.length)]) } }
    }

    private fun randomNonBlankText(random: Random, maxLen: Int = 24): String {
        var text = randomText(random, maxLen)
        while (text.isBlank()) text = randomText(random, maxLen)
        return text
    }

    private fun randomId(random: Random): String {
        val len = random.nextInt(1, 13)
        return buildString { repeat(len) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }
    }

    private fun randomMessage(random: Random): AgentChatMessage {
        val role = ROLES.random(random)
        // 往返恒等只对非空内容成立：tool 角色的空白内容行在编码时丢弃。
        val content = randomNonBlankText(random)
        val toolCallId = if (random.nextBoolean()) randomNonBlankText(random, 8) else null
        val toolName = if (random.nextBoolean()) randomNonBlankText(random, 8) else null
        return AgentChatMessage(role = role, content = content, toolCallId = toolCallId, toolName = toolName)
    }

    private fun randomSession(random: Random): AgentSession = AgentSession(
        id = randomId(random),
        title = randomNonBlankText(random),
        createdAt = random.nextLong(-1_000_000_000_000L, 1_000_000_000_000L),
        messages = List(random.nextInt(0, 6)) { randomMessage(random) }
    )

    private fun randomFieldName(random: Random): String {
        val stems = listOf(
            "future", "x", "v9", "extra", "unknown", "meta", "debug", "tmp",
            "id", "title", "messages", "createdAt", "role", "content"
        )
        return "${stems.random(random)}_${random.nextInt(10000)}"
    }

    private fun randomJsonValue(random: Random, depth: Int): Any {
        return when (random.nextInt(if (depth <= 0) 5 else 7)) {
            0 -> random.nextBoolean()
            1 -> random.nextLong(-1_000_000L, 1_000_000L)
            2 -> random.nextDouble()
            3 -> randomText(random)
            4 -> JSONObject.NULL
            5 -> JSONArray().apply { repeat(random.nextInt(0, 3)) { put(randomJsonValue(random, depth - 1)) } }
            else -> JSONObject().apply {
                repeat(random.nextInt(0, 3)) { put(randomFieldName(random), randomJsonValue(random, depth - 1)) }
            }
        }
    }

    private fun randomSessionObject(random: Random): JSONObject {
        val obj = JSONObject()
        if (random.nextBoolean()) obj.put("id", randomJsonValue(random, 0))
        if (random.nextBoolean()) obj.put("title", randomJsonValue(random, 0))
        if (random.nextBoolean()) obj.put("createdAt", randomJsonValue(random, 0))
        if (random.nextBoolean()) {
            val msgs = JSONArray()
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(4)) {
                    0 -> msgs.put(randomText(random))
                    1 -> msgs.put(random.nextInt(1000))
                    2 -> msgs.put(JSONObject.NULL)
                    else -> {
                        val m = JSONObject()
                        if (random.nextBoolean()) m.put("role", randomJsonValue(random, 0))
                        if (random.nextBoolean()) m.put("content", randomJsonValue(random, 0))
                        if (random.nextBoolean()) m.put("toolCallId", randomJsonValue(random, 0))
                        if (random.nextBoolean()) m.put("toolName", randomJsonValue(random, 0))
                        msgs.put(m)
                    }
                }
            }
            obj.put("messages", msgs)
        }
        repeat(random.nextInt(0, 3)) { obj.put(randomFieldName(random), randomJsonValue(random, 1)) }
        return obj
    }

    // 按生产规则归一化：空白 id 的会话丢弃、id 去空白、空白 title 回默认、
    // tool 角色空白内容行丢弃、空白 toolCallId/toolName 回 null。
    private fun normalize(sessions: List<AgentSession>): List<AgentSession> = sessions.mapNotNull { s ->
        val id = s.id.trim()
        if (id.isBlank()) return@mapNotNull null
        val messages = s.messages.filterNot { it.role == "tool" && it.content.isBlank() }.map {
            it.copy(
                toolCallId = it.toolCallId?.takeIf { v -> v.isNotBlank() },
                toolName = it.toolName?.takeIf { v -> v.isNotBlank() }
            )
        }
        s.copy(id = id, title = s.title.ifBlank { AgentSessionTitlePolicy.DEFAULT_TITLE }, messages = messages)
    }

    @Test
    fun `random garbage never throws and only yields well-formed sessions`() {
        val random = Random(0x91_2026)
        repeat(GARBAGE_ITERATIONS) { i ->
            val len = random.nextInt(0, 121)
            val raw = buildString {
                repeat(len) { append(GARBAGE_ALPHABET[random.nextInt(GARBAGE_ALPHABET.length)]) }
            }
            val decoded = AgentSessionCodec.parseSessions(raw)
            decoded.forEach { session ->
                assertTrue(
                    session.id.isNotBlank() && session.id == session.id.trim(),
                    "fuzz 迭代 #$i：id 必须去空白且非空"
                )
                assertTrue(session.title.isNotBlank(), "fuzz 迭代 #$i：title 必须非空")
                session.messages.forEach { m ->
                    assertTrue(
                        m.toolCallId == null || m.toolCallId.isNotBlank(),
                        "fuzz 迭代 #$i：toolCallId 只应是 null 或非空"
                    )
                    assertTrue(
                        m.toolName == null || m.toolName.isNotBlank(),
                        "fuzz 迭代 #$i：toolName 只应是 null 或非空"
                    )
                }
            }
        }
    }

    @Test
    fun `random valid sessions round trip exactly`() {
        val random = Random(0x92_2026)
        repeat(ROUND_TRIP_ITERATIONS) { i ->
            val sessions = List(random.nextInt(0, 5)) { randomSession(random) }
            val decoded = AgentSessionCodec.parseSessions(AgentSessionCodec.encodeSessions(sessions))
            assertEquals(sessions, decoded, "fuzz 迭代 #$i：编解码往返必须恒等")
        }
    }

    @Test
    fun `lossy edges normalize exactly as documented`() {
        val random = Random(0x93_2026)
        repeat(ROUND_TRIP_ITERATIONS) { i ->
            val sessions = List(random.nextInt(0, 5)) {
                val id = when (random.nextInt(4)) {
                    0 -> "  "
                    1 -> "  ${randomId(random)}  "
                    else -> randomId(random)
                }
                val title = if (random.nextBoolean()) randomText(random) else randomNonBlankText(random)
                val messages = List(random.nextInt(0, 5)) {
                    val role = ROLES.random(random)
                    val content = if (random.nextInt(4) == 0) "   " else randomNonBlankText(random)
                    AgentChatMessage(
                        role = role,
                        content = content,
                        toolCallId = if (random.nextBoolean()) "  " else randomNonBlankText(random, 8),
                        toolName = if (random.nextBoolean()) "" else null
                    )
                }
                AgentSession(id = id, title = title, createdAt = random.nextLong(), messages = messages)
            }
            val decoded = AgentSessionCodec.parseSessions(AgentSessionCodec.encodeSessions(sessions))
            assertEquals(normalize(sessions), decoded, "fuzz 迭代 #$i：有损边角必须按规则归一化")
        }
    }

    @Test
    fun `retainRecent keeps the last twelve in order for random lists`() {
        val random = Random(0x94_2026)
        repeat(ROUND_TRIP_ITERATIONS) { i ->
            val sessions = List(random.nextInt(0, 31)) { randomSession(random) }
            assertEquals(
                sessions.takeLast(AgentSessionCodec.MAX_STORED_SESSIONS),
                AgentSessionCodec.retainRecent(sessions),
                "fuzz 迭代 #$i：只保留最后 12 条且顺序不变"
            )
        }
    }

    @Test
    fun `structured random json arrays parse deterministically`() {
        val random = Random(0x95_2026)
        repeat(ROUND_TRIP_ITERATIONS) { i ->
            val array = JSONArray()
            repeat(random.nextInt(0, 6)) {
                when (random.nextInt(5)) {
                    0 -> array.put(randomText(random))
                    1 -> array.put(random.nextInt(1000))
                    2 -> array.put(JSONObject.NULL)
                    3 -> array.put(JSONArray())
                    else -> array.put(randomSessionObject(random))
                }
            }
            val raw = array.toString()
            val first = AgentSessionCodec.parseSessions(raw)
            val second = AgentSessionCodec.parseSessions(raw)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次解析必须相同")
            first.forEach { session ->
                assertTrue(
                    session.id.isNotBlank() && session.id == session.id.trim(),
                    "fuzz 迭代 #$i：id 必须去空白且非空"
                )
                assertTrue(session.title.isNotBlank(), "fuzz 迭代 #$i：title 必须非空")
            }
        }
    }
}
