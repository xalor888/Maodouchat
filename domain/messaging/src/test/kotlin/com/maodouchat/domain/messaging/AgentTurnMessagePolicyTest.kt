package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AgentChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTurnMessagePolicyTest {

    @Test
    fun `unknown tool error matches host wording`() {
        assertEquals(
            AgentToolHostPolicy.unknownTool("no_such_tool"),
            AgentTurnMessagePolicy.unknownTool("no_such_tool")
        )
        assertEquals(
            "Error: unknown tool no_such_tool",
            AgentTurnMessagePolicy.unknownTool("no_such_tool")
        )
    }

    @Test
    fun `denied and too many rounds strings`() {
        assertEquals("Error: tool denied", AgentTurnMessagePolicy.TOOL_DENIED)
        assertEquals("工具轮次过多，已停止", AgentTurnMessagePolicy.TOO_MANY_ROUNDS)
    }

    @Test
    fun `privileged hallucination note`() {
        assertEquals(
            "助手不能执行未审批的特权动作，请以本机实际结果为准。",
            AgentTurnMessagePolicy.PRIVILEGED_HALLUCINATION_NOTE
        )
    }

    @Test
    fun `drop duplicate last user drops trailing repeats only`() {
        val history = listOf(
            AgentChatMessage(role = "user", content = "hi"),
            AgentChatMessage(role = "assistant", content = "hello"),
            AgentChatMessage(role = "user", content = "again"),
            AgentChatMessage(role = "user", content = "again")
        )
        assertEquals(history.take(2), AgentTurnMessagePolicy.dropDuplicateLastUser(history, "again"))
    }

    @Test
    fun `drop duplicate last user keeps history without a trailing match`() {
        val history = listOf(
            AgentChatMessage(role = "user", content = "hi"),
            AgentChatMessage(role = "assistant", content = "hello")
        )
        assertEquals(history, AgentTurnMessagePolicy.dropDuplicateLastUser(history, "again"))
        assertEquals(emptyList<AgentChatMessage>(), AgentTurnMessagePolicy.dropDuplicateLastUser(emptyList(), "again"))
    }
}
