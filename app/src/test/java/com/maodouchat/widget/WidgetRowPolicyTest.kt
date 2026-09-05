package com.maodouchat.widget

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.domain.messaging.ConversationPrivacyCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetRowPolicyTest {

    private val labels = WidgetRowLabels(
        appName = "APP",
        genericPreview = "HIDDEN",
        lockedPreview = "LOCKED",
        attachmentLabel = "[ATT]",
    )
    private val openCaps = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = false)
    private val lockedCaps = ConversationPrivacyCapabilities(isSecretChat = false, isLocked = true)
    private val secretCaps = ConversationPrivacyCapabilities(isSecretChat = true, isLocked = false)

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
        val r = build(listOf(WidgetChatInput(chat("s"), secretCaps)))
        assertTrue(r.rows.isEmpty())
        assertEquals(0, r.totalUnread)
    }

    @Test
    fun lockedChatMasked() {
        val r = build(listOf(WidgetChatInput(chat("c", unread = 5, lastMessage = "secret"), lockedCaps)))
        assertEquals("APP", r.rows.single().title)
        assertEquals("LOCKED", r.rows.single().subtitle)
        assertEquals(5, r.rows.single().unread)
    }

    @Test
    fun appLockMasksAndZeroesBadge() {
        val r = build(
            listOf(WidgetChatInput(chat("c", unread = 5, lastMessage = "secret"), openCaps)),
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
            listOf(WidgetChatInput(chat("c", unread = 5), openCaps)),
            showUnreadBadge = false,
        )
        assertEquals(0, r.rows.single().unread)
    }

    @Test
    fun normalRowAndTotal() {
        val r = build(
            listOf(
                WidgetChatInput(chat("a", unread = 2, lastMessage = "hello"), openCaps),
                WidgetChatInput(chat("b", unread = 3), openCaps),
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
        val r = build(listOf(WidgetChatInput(c, openCaps)))
        assertEquals("very-long-ch", r.rows.single().title)
    }

    @Test
    fun maxRowsCap() {
        val inputs = (1..5).map { WidgetChatInput(chat("c$it"), openCaps) }
        val r = build(inputs, maxRows = 3)
        assertEquals(3, r.rows.size)
    }

    @Test
    fun blankIdsSkipped() {
        val r = build(listOf(WidgetChatInput(chat(" ", unread = 1), openCaps)))
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
}
