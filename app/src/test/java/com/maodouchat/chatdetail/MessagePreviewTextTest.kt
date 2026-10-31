package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MessagePreviewText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessagePreviewTextTest {

    @Test
    fun wireEnvelopeIsNeverShownInReply() {
        val wire = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}""",
            type = MessageType.TEXT,
        )
        val preview = MessagePreviewText.replyOrQuote(
            message = wire,
            mediaLabel = { "[media]" },
            encryptedPlaceholder = "[encrypted]",
        )
        assertEquals("[encrypted]", preview)
        assertFalse(preview.contains("algorithm"))
        assertFalse(preview.contains("version"))
    }

    @Test
    fun plaintextReplyKeepsBody() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello world",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello world",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun imageReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://x",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[image]",
            MessagePreviewText.replyOrQuote(msg, { "[image]" }, "[encrypted]"),
        )
    }

    @Test
    fun revokedMessageShowsEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "撤回了一条消息",
            type = MessageType.REVOKED,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun senderKeyDistributionEnvelopeShowsPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = """{"distributionMessage":"abc"}""",
            type = MessageType.SK_DIST,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun newlinesInTextReplyAreFoldedToSpaces() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello\nworld",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello world",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun blankTextFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "   ",
            type = MessageType.TEXT,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun voiceReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://voice",
            type = MessageType.VOICE,
        )
        assertEquals(
            "[voice]",
            MessagePreviewText.replyOrQuote(msg, { "[voice]" }, "[encrypted]"),
        )
    }

    @Test
    fun videoReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://video",
            type = MessageType.VIDEO,
        )
        assertEquals(
            "[video]",
            MessagePreviewText.replyOrQuote(msg, { "[video]" }, "[encrypted]"),
        )
    }

    @Test
    fun fileReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://doc",
            type = MessageType.FILE,
        )
        assertEquals(
            "[file]",
            MessagePreviewText.replyOrQuote(msg, { "[file]" }, "[encrypted]"),
        )
    }

    @Test
    fun locationReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "39.9,116.4",
            type = MessageType.LOCATION,
        )
        assertEquals(
            "[location]",
            MessagePreviewText.replyOrQuote(msg, { "[location]" }, "[encrypted]"),
        )
    }

    @Test
    fun gifReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://anim",
            type = MessageType.GIF,
        )
        assertEquals(
            "[gif]",
            MessagePreviewText.replyOrQuote(msg, { "[gif]" }, "[encrypted]"),
        )
    }

    @Test
    fun stickerReplyUsesMediaLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "sticker://1",
            type = MessageType.STICKER,
        )
        assertEquals(
            "[sticker]",
            MessagePreviewText.replyOrQuote(msg, { "[sticker]" }, "[encrypted]"),
        )
    }


    @Test
    fun surroundingWhitespaceInTextReplyIsTrimmed() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "  hello  ",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun newlineOnlyTextFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "\n\n",
            type = MessageType.TEXT,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun mediaTypeIgnoresEmptyContentAndStillUsesLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[image]",
            MessagePreviewText.replyOrQuote(msg, { "[image]" }, "[encrypted]"),
        )
    }

    @Test
    fun skDistWithPlainTextStillShowsPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello",
            type = MessageType.SK_DIST,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun textWithMetaSuffixShowsOnlyBodyBeforeMeta() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello<meta>{\"x\":1}</meta>",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun doubleNewlinesFoldToDoubleSpaces() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a\n\nb",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a  b",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun mediaLabelLambdaReceivesTheMessageType() {
        val image = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://x",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[IMAGE]",
            MessagePreviewText.replyOrQuote(image, { "[${it.name}]" }, "[encrypted]"),
        )
        val voice = Message(
            id = "m2",
            chatId = "c1",
            senderId = "u1",
            content = "file://v",
            type = MessageType.VOICE,
        )
        assertEquals(
            "[VOICE]",
            MessagePreviewText.replyOrQuote(voice, { "[${it.name}]" }, "[encrypted]"),
        )
    }

    @Test
    fun longTextIsNotTruncated() {
        val body = "a".repeat(500)
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = body,
            type = MessageType.TEXT,
        )
        assertEquals(
            body,
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun emptyMediaLabelFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://x",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "" }, "[encrypted]"),
        )
    }

    @Test
    fun metaOnlyTextFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "<meta>{\"x\":1}</meta>",
            type = MessageType.TEXT,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }


    @Test
    fun tabInsideTextReplyIsPreservedWhileNewlineFolds() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a\tb\nc",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a\tb c",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun carriageReturnInsideTextReplyIsPreserved() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a\rb",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a\rb",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun newlineInsideMediaLabelIsFolded() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://x",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[im age]",
            MessagePreviewText.replyOrQuote(msg, { "[im\nage]" }, "[encrypted]"),
        )
    }

    @Test
    fun spacesOnlyMediaLabelFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "file://x",
            type = MessageType.IMAGE,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "   " }, "[encrypted]"),
        )
    }

    @Test
    fun trailingNewlineInTextReplyIsTrimmed() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello\n",
            type = MessageType.TEXT,
        )
        assertEquals(
            "hello",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }


    @Test
    fun markdownTypeKeepsBody() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello **world**",
            type = MessageType.MARKDOWN,
        )
        assertEquals(
            "hello **world**",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun nudgeTypeKeepsBody() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "buzz",
            type = MessageType.NUDGE,
        )
        assertEquals(
            "buzz",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun crlfInTextReplyFoldsNewlineOnly() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a\r\nb",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a\r b",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun placeholderWithNewlineIsFolded() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "   ",
            type = MessageType.TEXT,
        )
        assertEquals(
            "[en crypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[en\ncrypted]"),
        )
    }

    @Test
    fun quotedEntityInTextReplyIsUnescaped() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "say &quot;hi&quot;",
            type = MessageType.TEXT,
        )
        assertEquals(
            "say \"hi\"",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun systemTypeKeepsPlainBody() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "joined",
            type = MessageType.SYSTEM,
        )
        assertEquals(
            "joined",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun locationEmptyContentStillUsesLabel() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "",
            type = MessageType.LOCATION,
        )
        assertEquals(
            "[location]",
            MessagePreviewText.replyOrQuote(msg, { "[location]" }, "[encrypted]"),
        )
    }

    @Test
    fun revokedIgnoresMediaLabelLambda() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "撤回了一条消息",
            type = MessageType.REVOKED,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[${it.name}]" }, "[encrypted]"),
        )
    }

    @Test
    fun ampEntityInTextReplyIsUnescaped() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a &amp; b",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a & b",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun stickerAndFileEmptyContentStillUseMediaLabel() {
        val sticker = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "",
            type = MessageType.STICKER,
        )
        assertEquals(
            "[sticker]",
            MessagePreviewText.replyOrQuote(sticker, { "[sticker]" }, "[encrypted]"),
        )
        val file = Message(
            id = "m2",
            chatId = "c1",
            senderId = "u1",
            content = "",
            type = MessageType.FILE,
        )
        assertEquals(
            "[file]",
            MessagePreviewText.replyOrQuote(file, { "[file]" }, "[encrypted]"),
        )
    }

    @Test
    fun revokedAndSkDistEmptyContentStillShowPlaceholder() {
        val revoked = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "",
            type = MessageType.REVOKED,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(revoked, { "[media]" }, "[encrypted]"),
        )
        val skDist = Message(
            id = "m2",
            chatId = "c1",
            senderId = "u1",
            content = "",
            type = MessageType.SK_DIST,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(skDist, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun singleNewlineTextFallsBackToEncryptedPlaceholder() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "\n",
            type = MessageType.TEXT,
        )
        assertEquals(
            "[encrypted]",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun newlineFoldsWhileTabBetweenNewlineAndTextIsPreserved() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a\n\tb",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a \tb",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun sameMessagePreviewIsStableOnRepeat() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello\nworld",
            type = MessageType.TEXT,
        )
        assertEquals(
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

    @Test
    fun tabPreservedWhileTrailingNewlineIsTrimmed() {
        val msg = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "a\tb\n",
            type = MessageType.TEXT,
        )
        assertEquals(
            "a\tb",
            MessagePreviewText.replyOrQuote(msg, { "[media]" }, "[encrypted]"),
        )
    }

}
