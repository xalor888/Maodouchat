package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSessionTitlePolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val ROLES = listOf("user", "assistant", "system", "tool", "User", "USER", "user ")
        private val CONTENTS = listOf(
            "", "x", "hello world", "x".repeat(40), "你好".repeat(20),
            "  spaces  ", "a".repeat(24), "b".repeat(25), "multi\nline"
        )
        private val TITLES = listOf("新对话", "旧标题", "", "x".repeat(30), "  padded  ")
    }

    private fun randomMessages(random: Random): List<AgentChatMessage> {
        val count = random.nextInt(0, 6)
        return List(count) {
            AgentChatMessage(role = ROLES.random(random), content = CONTENTS.random(random))
        }
    }

    @Test
    fun `random message lists derive title from first user message`() {
        val random = Random(0xD8_2026)
        repeat(ITERATIONS) { i ->
            val messages = randomMessages(random)
            val current = TITLES.random(random)
            val title = AgentSessionTitlePolicy.deriveTitle(messages, current)
            val expected = messages.firstOrNull { it.role == "user" }?.content
                ?.take(AgentSessionTitlePolicy.MAX_SESSION_TITLE_CHARS) ?: current
            assertEquals(expected, title, "fuzz 迭代 #$i：标题应取首条 user 消息截断或回落原标题：$messages")
        }
    }

    @Test
    fun `only the exact user role counts`() {
        val messages = listOf(
            AgentChatMessage(role = "User", content = "capital"),
            AgentChatMessage(role = "USER", content = "upper"),
            AgentChatMessage(role = "user ", content = "trailing space"),
            AgentChatMessage(role = "system", content = "sys")
        )
        assertEquals("旧标题", AgentSessionTitlePolicy.deriveTitle(messages, "旧标题"))
        val withUser = messages + AgentChatMessage(role = "user", content = "real user")
        assertEquals("real user", AgentSessionTitlePolicy.deriveTitle(withUser, "旧标题"))
    }

    @Test
    fun `second user message never wins`() {
        val random = Random(0xD9_2026)
        repeat(ITERATIONS) { i ->
            val first = CONTENTS.random(random)
            val second = CONTENTS.random(random)
            val messages = listOf(
                AgentChatMessage(role = "assistant", content = "welcome"),
                AgentChatMessage(role = "user", content = first),
                AgentChatMessage(role = "user", content = second)
            )
            assertEquals(
                first.take(AgentSessionTitlePolicy.MAX_SESSION_TITLE_CHARS),
                AgentSessionTitlePolicy.deriveTitle(messages, "新对话"),
                "fuzz 迭代 #$i：只有首条 user 消息能定标题"
            )
        }
    }

    @Test
    fun `empty and blank first user contents stay as-is truncated`() {
        val random = Random(0xDA_2026)
        repeat(ITERATIONS) { i ->
            val content = listOf("", " ", "   ", "\n", "x".repeat(30)).random(random)
            val messages = listOf(AgentChatMessage(role = "user", content = content))
            assertEquals(
                content.take(AgentSessionTitlePolicy.MAX_SESSION_TITLE_CHARS),
                AgentSessionTitlePolicy.deriveTitle(messages, "新对话"),
                "fuzz 迭代 #$i：空白内容不回落原标题、只截断"
            )
        }
    }

    @Test
    fun `fallback title passes through untouched without user messages`() {
        val random = Random(0xDB_2026)
        repeat(ITERATIONS) { i ->
            val messages = List(random.nextInt(0, 4)) {
                AgentChatMessage(role = listOf("assistant", "system", "tool").random(random), content = CONTENTS.random(random))
            }
            val current = TITLES.random(random)
            assertEquals(current, AgentSessionTitlePolicy.deriveTitle(messages, current), "fuzz 迭代 #$i：无 user 消息时原标题应原样透出")
        }
    }
}
