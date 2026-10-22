package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.NudgeDisplayPolicy
import com.maodouchat.ui.screen.chatdetail.Templates
import kotlin.test.Test
import kotlin.test.assertEquals

class NudgeDisplayPolicyTest {

    private val templates = Templates(
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

    @Test
    fun ownMessageWithPlainStoredContentRebuildsYouForm() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "hello",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍hello", text)
    }

    @Test
    fun englishPrefixIsCaseInsensitive() {
        assertEquals("Alex", NudgeDisplayPolicy.extractTargetName("you NUDGED Alex"))
    }

    @Test
    fun blankSenderFallsBackToEllipsis() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "  ",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("… 拍了拍你", text)
    }

    @Test
    fun blankStoredContentFallsBackToEllipsisInGroup() {
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
    fun extractTargetNameBlankInput() {
        assertEquals("", NudgeDisplayPolicy.extractTargetName(""))
        assertEquals("", NudgeDisplayPolicy.extractTargetName("   "))
    }

    @Test
    fun extractTargetNameTrimsSurroundingWhitespace() {
        assertEquals("张三", NudgeDisplayPolicy.extractTargetName("你拍了拍 张三  "))
        assertEquals("Alex", NudgeDisplayPolicy.extractTargetName("You nudged   Alex "))
    }

    @Test
    fun extractTargetNamePrefixOnlyReturnsEmpty() {
        assertEquals("", NudgeDisplayPolicy.extractTargetName("你拍了拍"))
        assertEquals("", NudgeDisplayPolicy.extractTargetName("You nudged "))
    }

    @Test
    fun nonOwnDirectWithPlainContentRebuildsTheyYou() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "hello",
            senderDisplayName = "李四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", text)
    }

    @Test
    fun nonOwnGroupWithPlainContentUsesRawTextAsTarget() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "hello",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍hello", text)
    }

    @Test
    fun ownMessageWithBlankStoredContentReturnsStoredAsIs() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "  ",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("  ", text)
    }
}
