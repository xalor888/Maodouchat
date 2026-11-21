package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ForwardMergePolicy fuzz（清单 931 行第十六块，簇 4）。
 *
 * 源码实测的不变式（Python 迷你模型校验截断算术）：
 * - 可合并当且仅当条数 > 1 且全部为 TEXT/MARKDOWN；
 * - 每条先 parsedContent().take(4000) 再加标签（全角冒号 "："），整体再 take(4000)，标签本身也占额度；
 * - 未知发送者（participants 为 null、无匹配、displayName 空白）恒为裸 content；
 * - displayName 永不为空白除非 id 为空（nickname → name → id 回退），名字空白时回退到 id。
 */
class ForwardMergePolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val decimalEntity = Regex("&#(\\d{1,7});")
        private val hexEntity = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)
        private val contentFragments = listOf(
            "hello", "hi", "one", "two", "a", "b", " ", "x", "hello world", "&amp;", "&lt;",
            "&#65;", "&#x42;", "<meta>{}", "<meta>{\"a\":1}</meta>", "100%"
        )
        private val senderIds = listOf("u1", "u2", "u3")
        private val participantIds = listOf("u1", "u2", "u3", "u9")
        private val names = listOf("Alice", "Bob", "Carol", "", "   ")
    }

    private fun message(id: String, senderId: String = "u1", content: String = "hi", type: MessageType = MessageType.TEXT) =
        Message(id = id, chatId = "c1", senderId = senderId, content = content, type = type, timestamp = 1L)

    private fun randomContent(random: Random): String = buildString {
        repeat(random.nextInt(0, 5)) { append(contentFragments[random.nextInt(contentFragments.size)]) }
    }

    private fun randomUser(random: Random): User {
        val nickname = if (random.nextBoolean()) null else listOf("Al", "Bo", "", "  ")[random.nextInt(4)]
        return User(
            id = participantIds[random.nextInt(participantIds.size)],
            name = names[random.nextInt(names.size)],
            nickname = nickname
        )
    }

    private fun unescapeOracle(raw: String): String {
        if (raw.isEmpty() || '&' !in raw) return raw
        var out = decimalEntity.replace(raw) { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        out = hexEntity.replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
                String(intArrayOf(code), 0, 1)
            } ?: match.value
        }
        return out
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun parsedContentOracle(content: String): String {
        val idx = content.lastIndexOf("<meta>")
        val body = if (idx < 0) content else content.substring(0, idx)
        return unescapeOracle(body)
    }

    private fun mergedOracle(messages: List<Message>, currentUserId: String, meLabel: String, participants: List<User>?): String =
        messages.joinToString("\n") { msg ->
            val senderLabel = when {
                msg.senderId == currentUserId -> meLabel
                else -> participants?.firstOrNull { it.id == msg.senderId }?.displayName?.takeIf { it.isNotBlank() }
            }
            val content = parsedContentOracle(msg.content).take(4_000)
            senderLabel?.let { "$it：$content" } ?: content
        }.take(4_000)

    @Test
    fun `isMergeableMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val count = random.nextInt(0, 7)
            val messages = List(count) { j ->
                message(
                    "m$j",
                    senderId = senderIds[random.nextInt(senderIds.size)],
                    type = MessageType.entries[random.nextInt(MessageType.entries.size)]
                )
            }
            val expected = messages.size > 1 &&
                messages.all { it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN }
            assertEquals(
                expected,
                ForwardMergePolicy.isMergeable(messages),
                "fuzz 迭代 #$i：${messages.map { it.type }} 可合并当且仅当多于一条且全为文本"
            )
        }
    }

    @Test
    fun `mergedTextMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val count = random.nextInt(2, 6)
            val messages = List(count) { j ->
                message(
                    "m$j",
                    senderId = senderIds[random.nextInt(senderIds.size)],
                    content = randomContent(random),
                    type = if (random.nextBoolean()) MessageType.TEXT else MessageType.MARKDOWN
                )
            }
            val participants = if (random.nextBoolean()) null
            else List(random.nextInt(0, 4)) { randomUser(random) }
            assertEquals(
                mergedOracle(messages, "u1", "我", participants),
                ForwardMergePolicy.buildMergedText(messages, "u1", "我", participants),
                "fuzz 迭代 #$i：合并文本须与逐字 oracle 全等"
            )
        }
    }

    @Test
    fun `truncationBoundaries`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val length = listOf(3999, 4000, 4001, 5000)[random.nextInt(4)]
            val text = ForwardMergePolicy.buildMergedText(
                listOf(message("m1", senderId = "u2", content = "x".repeat(length)), message("m2", senderId = "u2", content = "y")),
                "u1", "我", null
            )
            assertEquals(4_000, text.length, "fuzz 迭代 #$i：单条 $length 字时整体恒截断到 4000")
            if (length >= 4000) {
                assertEquals("x".repeat(4_000), text, "fuzz 迭代 #$i：单条超长时整体即前 4000 个 x")
            }
        }
        val two2000 = ForwardMergePolicy.buildMergedText(
            listOf(message("m1", senderId = "u2", content = "x".repeat(2_000)), message("m2", senderId = "u2", content = "y".repeat(2_000))),
            "u1", "我", null
        )
        assertEquals(4_000, two2000.length, "两条 2000 字（含换行 4001 字）整体恰 4000")
        val labelled = ForwardMergePolicy.buildMergedText(
            listOf(message("m1", senderId = "u1", content = "x".repeat(4_000)), message("m2", senderId = "u2", content = "y")),
            "u1", "我", null
        )
        assertEquals(4_000, labelled.length, "标签挤占额度时整体仍恰 4000")
        assertTrue(labelled.startsWith("我："), "标签在整体截断后仍保留在行首")
    }

    @Test
    fun `metaAndEntitiesUseParsedContent`() {
        val encoded = ForwardMergePolicy.buildMergedText(
            listOf(
                message("m1", senderId = "u2", content = "a&amp;b<meta>{}"),
                message("m2", senderId = "u2", content = "&#65;&#x42;<meta>{}")
            ),
            "u1", "我", null
        )
        assertEquals("a&b\nAB", encoded, "合并用 parsedContent：meta 后缀剥掉、实体解开")
        val random = Random(0x16_2026)
        repeat(100) { i ->
            val messages = List(random.nextInt(2, 5)) { j ->
                message("m$j", senderId = senderIds[random.nextInt(senderIds.size)], content = randomContent(random))
            }
            assertEquals(
                mergedOracle(messages, "u1", "我", null),
                ForwardMergePolicy.buildMergedText(messages, "u1", "我", null),
                "fuzz 迭代 #$i：meta/实体随机内容仍与 oracle 全等"
            )
        }
    }

    @Test
    fun `labelCounterexamples`() {
        val withImage = listOf(
            message("m1", senderId = "u1", content = "one"),
            message("m2", senderId = "u2", content = "two", type = MessageType.IMAGE)
        )
        assertFalse(ForwardMergePolicy.isMergeable(withImage), "含任一非文本类型即不可合并")
        val duplicateParticipants = listOf(User(id = "u2", name = "First"), User(id = "u2", name = "Second"))
        assertEquals(
            "我：one\nFirst：two",
            ForwardMergePolicy.buildMergedText(
                listOf(message("m1", senderId = "u1", content = "one"), message("m2", senderId = "u2", content = "two")),
                "u1", "我", duplicateParticipants
            ),
            "同 id 参与者出现两次取第一个"
        )
        assertEquals(
            "two\n我：one",
            ForwardMergePolicy.buildMergedText(
                listOf(message("m1", senderId = "u2", content = "two"), message("m2", senderId = "u1", content = "one")),
                "u1", "我", null
            ),
            "participants 为 null 时他人消息恒无标签"
        )
        val blankId = listOf(User(id = "", name = ""))
        assertEquals(
            "ghost\n我：one",
            ForwardMergePolicy.buildMergedText(
                listOf(message("m1", senderId = "", content = "ghost"), message("m2", senderId = "u1", content = "one")),
                "u1", "我", blankId
            ),
            "id 为空的参与者 displayName 为空，恒为裸 content"
        )
        val blankName = listOf(User(id = "u2", name = "   "))
        assertEquals(
            "u2：two\n我：one",
            ForwardMergePolicy.buildMergedText(
                listOf(message("m1", senderId = "u2", content = "two"), message("m2", senderId = "u1", content = "one")),
                "u1", "我", blankName
            ),
            "名字空白时 displayName 回退到 id"
        )
        val selfAlsoParticipant = listOf(User(id = "u1", name = "Alice"))
        assertEquals(
            "我：one\n我：two",
            ForwardMergePolicy.buildMergedText(
                listOf(message("m1", senderId = "u1", content = "one"), message("m2", senderId = "u1", content = "two")),
                "u1", "我", selfAlsoParticipant
            ),
            "自己在参与者列表里仍优先用 meLabel"
        )
    }
}
