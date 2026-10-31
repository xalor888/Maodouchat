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

    @Test
    fun extractTargetName_rejectsNonPrefixAndMidStringPrefix() {
        assertEquals("", NudgeDisplayPolicy.extractTargetName("hello"))
        assertEquals("", NudgeDisplayPolicy.extractTargetName("拍了拍张三"))
        assertEquals("", NudgeDisplayPolicy.extractTargetName("hi 你拍了拍张三"))
        assertEquals("", NudgeDisplayPolicy.extractTargetName("You nudgedAlex"))
    }

    @Test
    fun extractTargetName_englishAllCapsPrefix() {
        assertEquals("Bob", NudgeDisplayPolicy.extractTargetName("YOU NUDGED Bob"))
        assertEquals("Bob", NudgeDisplayPolicy.extractTargetName("You Nudged Bob"))
    }

    @Test
    fun ownMessage_englishStoredAndGroupFlagRebuildsYouForm() {
        val direct = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "You nudged Alex",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍Alex", direct)
        val group = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "你拍了拍张三",
            senderDisplayName = "ignored",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("你拍了拍张三", group)
    }

    @Test
    fun nonOwnGroup_englishStoredUsesExtractedTarget() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "You nudged Alex",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍Alex", text)
    }

    @Test
    fun nonOwn_senderNameTrimmedInBothChatKinds() {
        val direct = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = " 李四 ",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", direct)
        val group = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = " 李四 ",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍张三", group)
    }

    @Test
    fun nonOwnDirect_blankStoredContentStillShowsTheyNudgedYou() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "   ",
            senderDisplayName = "李四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", text)
    }

    @Test
    fun nonOwnGroup_blankStoredAndBlankSender_doubleEllipsis() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "   ",
            senderDisplayName = "  ",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("… 拍了拍…", text)
    }

    @Test
    fun nonOwnGroup_blankSenderButPresentTarget() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "  ",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("… 拍了拍张三", text)
    }

    @Test
    fun extractTargetName_keepsInternalSpacesInTarget() {
        assertEquals("张 三", NudgeDisplayPolicy.extractTargetName("你拍了拍张 三"))
        assertEquals("Mary Jane", NudgeDisplayPolicy.extractTargetName("You nudged Mary Jane"))
    }

    @Test
    fun ownMessage_targetTrailingPunctuationKept() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "你拍了拍张三!",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍张三!", text)
    }

    @Test
    fun ownMessage_prefixOnlyStoredRebuildsFromTrimmedRaw() {
        val english = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "You nudged ",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍You nudged", english)
        val chinese = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "你拍了拍",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍你拍了拍", chinese)
    }

    @Test
    fun nonOwnDirect_englishStoredStillShowsTheyNudgedYou() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "You nudged Alex",
            senderDisplayName = "李四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", text)
    }

    @Test
    fun extractTargetName_nestedPrefixStripsOnlyFirst() {
        assertEquals("你拍了拍张三", NudgeDisplayPolicy.extractTargetName("你拍了拍你拍了拍张三"))
    }

    @Test
    fun nonOwn_senderInternalSpacesPreserved() {
        val direct = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "李 四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李 四 拍了拍你", direct)
        val group = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍张三",
            senderDisplayName = "李 四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李 四 拍了拍张三", group)
    }

    @Test
    fun ownMessage_englishStoredTrailingSpacesTrimmed() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "You nudged   Alex  ",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍Alex", text)
    }


    @Test
    fun extractTargetNameKeepsTrailingPunctuation() {
        assertEquals("张三！", NudgeDisplayPolicy.extractTargetName("你拍了拍张三！"))
        assertEquals("Alex?", NudgeDisplayPolicy.extractTargetName("You nudged Alex?"))
    }

    @Test
    fun ownMessageGroupFlagIgnoredForPlainContent() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "hello",
            senderDisplayName = "ignored",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("你拍了拍hello", text)
    }

    @Test
    fun nonOwnDirectEnglishStoredIgnoresTarget() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "You nudged Alex",
            senderDisplayName = "李四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", text)
    }

    @Test
    fun extractTargetNameTabSeparatedTarget() {
        assertEquals("张三", NudgeDisplayPolicy.extractTargetName("你拍了拍\t张三"))
    }

    @Test
    fun ownMessageEnglishStoredInGroupRebuildsYouForm() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "You nudged Alex",
            senderDisplayName = "ignored",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("你拍了拍Alex", text)
    }

    @Test
    fun extractTargetName_englishPrefixRequiresSpaceSeparator() {
        assertEquals("", NudgeDisplayPolicy.extractTargetName("You nudged\tAlex"))
        assertEquals("Alex", NudgeDisplayPolicy.extractTargetName("You nudged  Alex"))
    }

    @Test
    fun ownMessageIgnoresBlankSenderName() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "你拍了拍张三",
            senderDisplayName = "",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍张三", text)
    }

    @Test
    fun nonOwnGroupTrimsPaddedTarget() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍  张三  ",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍张三", text)
    }

    @Test
    fun ownMessageEnglishMultiWordTargetRebuildsYouForm() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "You nudged Mary Jane",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍Mary Jane", text)
    }

    @Test
    fun nonOwnGroupPaddedPlainContentTrimsFallback() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "  hello  ",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍hello", text)
    }

    @Test
    fun extractTargetNameStripsLeadingWhitespaceBeforePrefix() {
        assertEquals("张三", NudgeDisplayPolicy.extractTargetName("  你拍了拍张三  "))
        assertEquals("Alex", NudgeDisplayPolicy.extractTargetName("  You nudged Alex "))
    }

    @Test
    fun ownMessagePaddedPlainContentRebuildsYouForm() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "  hello  ",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍hello", text)
    }

    @Test
    fun nonOwnDirectPaddedPlainContentStillShowsTheyNudgedYou() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "  hello  ",
            senderDisplayName = "李四",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("李四 拍了拍你", text)
    }

    @Test
    fun ownMessageNudgedWithoutTrailingSpaceRebuildsFromRawText() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "You nudged",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍You nudged", text)
    }

    @Test
    fun extractTargetNamePrefixOnlyWithSurroundingWhitespaceReturnsEmpty() {
        assertEquals("", NudgeDisplayPolicy.extractTargetName("   你拍了拍   "))
        assertEquals("", NudgeDisplayPolicy.extractTargetName("  You nudged  "))
    }

    @Test
    fun nonOwnGroupWithoutTrailingSpaceTargetFallsBackToRaw() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "You nudged",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍You nudged", text)
    }

    @Test
    fun ownMessageTabSeparatedTargetRebuildsYouForm() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "你拍了拍\t张三",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("你拍了拍张三", text)
    }

    @Test
    fun nonOwnGroupPrefixOnlyStoredRebuildsFromRaw() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "你拍了拍",
            senderDisplayName = "李四",
            isDirectChat = false,
            templates = templates
        )
        assertEquals("李四 拍了拍你拍了拍", text)
    }

    @Test
    fun nonOwnDirectEmptyStoredAndBlankSenderShowsEllipsis() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = false,
            storedContent = "",
            senderDisplayName = "  ",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("… 拍了拍你", text)
    }

    @Test
    fun ownMessageEmptyStoredReturnsEmpty() {
        val text = NudgeDisplayPolicy.displayText(
            isOwnMessage = true,
            storedContent = "",
            senderDisplayName = "ignored",
            isDirectChat = true,
            templates = templates
        )
        assertEquals("", text)
    }
}
