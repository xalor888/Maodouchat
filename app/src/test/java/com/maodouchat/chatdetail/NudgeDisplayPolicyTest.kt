package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class NudgeDisplayPolicyTest {

    private val templates = NudgeDisplayPolicy.Templates(
        youNudged = { t -> "你拍了拍$t" },
        theyNudgedYou = { s -> "$s 拍了拍你" },
        theyNudgedTarget = { s, t -> "$s 拍了拍$t" }
    )

    @Test
    fun ownMessageKeepsYouNudgedForm() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "你拍了拍张三",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍张三", text)
    }

    @Test
    fun directPeerSeesTheyNudgedYou() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "李四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", text)
    }

    @Test
    fun groupPeerSeesTheyNudgedTarget() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍张三", text)
    }

    @Test
    fun extractTargetSupportsEnglishPrefix() {
        assertEquals("Alex", NudgeDisplayPolicy.extractTargetName("You nudged Alex"))
        assertEquals("张三", NudgeDisplayPolicy.extractTargetName("你拍了拍张三"))
    }
}
