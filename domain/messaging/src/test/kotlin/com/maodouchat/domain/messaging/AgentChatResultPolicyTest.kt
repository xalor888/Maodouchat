package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentChatResultPolicyTest {

    @Test
    fun `nickname result distinguishes set and clear`() {
        assertEquals("Set nickname for u_1", AgentChatResultPolicy.nicknameResult("u_1", "阿毛"))
        assertEquals("Cleared nickname for u_1", AgentChatResultPolicy.nicknameResult("u_1", ""))
        assertEquals("Cleared nickname for u_1", AgentChatResultPolicy.nicknameResult("u_1", "   "))
    }

    @Test
    fun `marked read result carries item id`() {
        assertEquals("Marked read n_1", AgentChatResultPolicy.markedRead("n_1"))
    }
}
