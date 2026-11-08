package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRowPolicyTest {

    private val labels = WidgetRowLabels(
        appName = "APP",
        genericPreview = "HIDDEN",
        lockedPreview = "LOCKED",
        attachmentLabel = "[ATT]",
    )
    private fun openInput(c: Chat) = WidgetChatInput(c, previewAllowed = true, isLocked = false)
    private fun lockedInput(c: Chat) = WidgetChatInput(c, previewAllowed = true, isLocked = true)
    private fun secretInput(c: Chat) = WidgetChatInput(c, previewAllowed = false, isLocked = false)

    private fun chat(
        id: String,
        unread: Int = 0,
        lastMessage: String = "hi",
        type: MessageType = MessageType.TEXT,
        groupName: String? = null,
        peer: User? = User(id = "peer", name = "Peer"),
        lastTime: Long = 100,
    ) = Chat(
        id = id, unreadCount = unread, lastMessage = lastMessage, lastMessageType = type,
        groupName = groupName, isGroup = groupName != null, lastMessageTime = lastTime,
        participants = listOfNotNull(peer, User(id = "me", name = "Me")),
    )

    private fun build(
        inputs: List<WidgetChatInput>,
        showUnreadBadge: Boolean = true,
        appLockOn: Boolean = false,
        maxRows: Int = 10,
    ) = buildWidgetRows(
        inputs = inputs, ownerUserId = "me", showUnreadBadge = showUnreadBadge,
        appLockOn = appLockOn, labels = labels, maxRows = maxRows, timeLabel = { "T$it" },
    )

    @Test
    fun secretChatsNeverOnDesk() {
        val r = build(listOf(secretInput(chat("s"))))
        assertTrue(r.rows.isEmpty())
        assertEquals(0, r.totalUnread)
    }

    @Test
    fun lockedChatMasked() {
        val r = build(listOf(lockedInput(chat("c", unread = 5, lastMessage = "secret"))))
        assertEquals("APP", r.rows.single().title)
        assertEquals("LOCKED", r.rows.single().subtitle)
        assertEquals(5, r.rows.single().unread)
    }

    @Test
    fun appLockMasksAndZeroesBadge() {
        val r = build(
            listOf(openInput(chat("c", unread = 5, lastMessage = "secret"))),
            appLockOn = true,
        )
        assertEquals("APP", r.rows.single().title)
        assertEquals("HIDDEN", r.rows.single().subtitle)
        assertEquals(0, r.rows.single().unread)
        assertEquals(0, r.totalUnread)
    }

    @Test
    fun badgeOffZeroesUnread() {
        val r = build(
            listOf(openInput(chat("c", unread = 5))),
            showUnreadBadge = false,
        )
        assertEquals(0, r.rows.single().unread)
    }

    @Test
    fun normalRowAndTotal() {
        val r = build(
            listOf(
                openInput(chat("a", unread = 2, lastMessage = "hello")),
                openInput(chat("b", unread = 3)),
            )
        )
        assertEquals("Peer", r.rows[0].title)
        assertEquals("hello", r.rows[0].subtitle)
        assertEquals("T100", r.rows[0].timeLabel)
        assertEquals(5, r.totalUnread)
    }

    @Test
    fun titleFallsBackToTruncatedId() {
        val c = chat("very-long-chat-id-123", peer = null).copy(isGroup = false, groupName = null)
        val r = build(listOf(openInput(c)))
        assertEquals("very-long-ch", r.rows.single().title)
    }

    @Test
    fun maxRowsCap() {
        val inputs = (1..5).map { openInput(chat("c$it")) }
        val r = build(inputs, maxRows = 3)
        assertEquals(3, r.rows.size)
    }

    @Test
    fun blankIdsSkipped() {
        val r = build(listOf(openInput(chat(" ", unread = 1))))
        assertTrue(r.rows.isEmpty())
    }

    @Test
    fun attachmentPreviewUsesLabel() {
        assertEquals("[ATT]", widgetPreviewOf("x", MessageType.IMAGE, "[ATT]"))
        assertEquals("hi", widgetPreviewOf("hi", MessageType.TEXT, "[ATT]"))
        assertEquals("a".repeat(40) + "…", widgetPreviewOf("a".repeat(50), MessageType.TEXT, "[ATT]"))
    }

    @Test
    fun groupTitleUsesGroupName() {
        val c = chat("g", groupName = "Group!")
        assertEquals("Group!", widgetChatTitle(c, "me"))
        assertNull(widgetChatTitle(chat("d", peer = null).copy(groupName = null), "me"))
    }

    @Test
    fun markdownPreviewPassesTextThrough() {
        assertEquals("hello", widgetPreviewOf("hello", MessageType.MARKDOWN, "[ATT]"))
    }

    @Test
    fun metaTagSuffixIsStrippedAndTrimmed() {
        val raw = "hello  " + Message.META_TAG_PREFIX + """{"k":1}"""
        assertEquals("hello", widgetPreviewOf(raw, MessageType.TEXT, "[ATT]"))
    }

    @Test
    fun exactlyFortyCharsNeedsNoEllipsis() {
        assertEquals("a".repeat(40), widgetPreviewOf("a".repeat(40), MessageType.TEXT, "[ATT]"))
        assertEquals("a".repeat(40) + "…", widgetPreviewOf("a".repeat(41), MessageType.TEXT, "[ATT]"))
    }

    @Test
    fun lockedChatUnderAppLockKeepsLockedPreviewButZeroesBadge() {
        val r = build(
            listOf(lockedInput(chat("c", unread = 5, lastMessage = "secret"))),
            appLockOn = true,
        )
        assertEquals("APP", r.rows.single().title)
        assertEquals("LOCKED", r.rows.single().subtitle)
        assertEquals(0, r.rows.single().unread)
        assertEquals(0, r.totalUnread)
    }

    @Test
    fun skippedSecretChatsDoNotConsumeMaxRows() {
        val inputs = listOf(
            secretInput(chat("s")),
            openInput(chat("a")),
            openInput(chat("b")),
            openInput(chat("c")),
        )
        val r = build(inputs, maxRows = 2)
        assertEquals(listOf("a", "b"), r.rows.map { it.chatId })
    }
}
