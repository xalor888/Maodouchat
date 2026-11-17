package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTaskLinePolicyTest {

    @Test
    fun `taskLine formats all fields tab separated`() {
        assertEquals(
            "t1\tc1\t买牛奶\tcompleted=false\tdue=明天 9 点",
            AgentTaskLinePolicy.taskLine("t1", "c1", "买牛奶", false, "明天 9 点"),
        )
    }

    @Test
    fun `taskLine leaves due empty when no due text`() {
        assertEquals(
            "t2\t\t开会\tcompleted=true\tdue=",
            AgentTaskLinePolicy.taskLine("t2", "", "开会", true, null),
        )
    }
}
