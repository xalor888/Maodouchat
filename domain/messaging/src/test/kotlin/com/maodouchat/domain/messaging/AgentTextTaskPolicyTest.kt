package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiContextMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTextTaskPolicyTest {

    @Test
    fun `transcriptLine joins sender and text with colon`() {
        assertEquals("小明: 你好", AgentTextTaskPolicy.transcriptLine("小明", "你好"))
    }

    @Test
    fun `transcript joins messages with newlines`() {
        assertEquals(
            "小明: 你好\n小红: 在吗",
            AgentTextTaskPolicy.transcript(
                listOf(AiContextMessage("小明", "你好"), AiContextMessage("小红", "在吗")),
            ),
        )
        assertEquals("", AgentTextTaskPolicy.transcript(emptyList()))
    }

    @Test
    fun `rewriteInstruction uses mode instruction without style`() {
        assertEquals(
            "改写下面的草稿：缩短，保留原意。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteInstruction("shorten", null, null),
        )
    }

    @Test
    fun `rewriteInstruction appends style hint after a space`() {
        assertEquals(
            "改写下面的草稿：更正式礼貌。只输出改写结果，不要解释。 语气正式",
            AgentTextTaskPolicy.rewriteInstruction("formal", null, "语气正式"),
        )
        assertEquals(
            "改写下面的草稿：缩短，保留原意。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteInstruction("shorten", null, "  "),
        )
    }
}
