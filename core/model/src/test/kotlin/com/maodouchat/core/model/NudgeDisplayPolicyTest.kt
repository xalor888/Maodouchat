package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeDisplayPolicyTest {

    private val templates = Templates(
        youNudged = { t -> "你拍了拍$t" },
        theyNudgedYou = { s -> "$s 拍了拍你" },
        theyNudgedTarget = { s, t -> "$s 拍了拍$t" }
    )

    @Test
    fun `group peer with blank target falls back to ellipsis`() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "   ",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍…", text)
    }

    @Test
    fun `direct peer with blank sender falls back to ellipsis`() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "   ",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("… 拍了拍你", text)
    }
}
