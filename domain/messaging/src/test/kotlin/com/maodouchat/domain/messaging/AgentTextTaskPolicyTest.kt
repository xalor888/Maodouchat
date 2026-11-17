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
    fun `translateInstruction falls back to Chinese for blank language`() {
        assertEquals(
            "把下面文本翻译成 中文。只输出译文。",
            AgentTextTaskPolicy.translateInstruction("  "),
        )
    }

    @Test
    fun `translateInstruction trims the target language`() {
        assertEquals(
            "把下面文本翻译成 英语。只输出译文。",
            AgentTextTaskPolicy.translateInstruction(" 英语 "),
        )
    }

    @Test
    fun `suggestInstruction keeps a known tone`() {
        assertEquals(
            "根据对话写 2 条可直接发送的回复，语气 warm。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction(" warm ", 2),
        )
    }

    @Test
    fun `suggestInstruction falls back to friendly for unknown tones`() {
        assertEquals(
            "根据对话写 2 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", 2),
        )
    }

    @Test
    fun `suggestInstruction clamps count to 1 to 4`() {
        assertEquals(
            "根据对话写 1 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", 0),
        )
        assertEquals(
            "根据对话写 4 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", 9),
        )
    }

    @Test
    fun `summarizeInstruction maps known styles`() {
        assertEquals(
            "总结这些本机已解密消息。风格：只列已做出的决定。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("decisions"),
        )
    }

    @Test
    fun `summarizeInstruction defaults unknown styles`() {
        assertEquals(
            "总结这些本机已解密消息。风格：简要概括。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("unknown"),
        )
    }

    @Test
    fun `groupAssistantInstruction keeps known modes and trims the query`() {
        assertEquals(
            "用本机已解密的群聊上下文回答。模式=summary。用户问题：本周进展？",
            AgentTextTaskPolicy.groupAssistantInstruction(" summary ", "  本周进展？ "),
        )
        assertEquals(
            "用本机已解密的群聊上下文回答。模式=answer。用户问题：hi",
            AgentTextTaskPolicy.groupAssistantInstruction("chat", "hi"),
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
