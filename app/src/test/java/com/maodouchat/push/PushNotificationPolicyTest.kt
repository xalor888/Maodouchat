package com.maodouchat.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushNotificationPolicyTest {

    @Test
    fun suppressesBlankChat() {
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "",
                activeChatId = null,
                chatNotificationsMuted = false
            )
        )
    }

    @Test
    fun suppressesActiveChat() {
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "c1",
                activeChatId = "c1",
                chatNotificationsMuted = false
            )
        )
    }

    @Test
    fun suppressesSkDist() {
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "SK_DIST",
                chatId = "c1",
                activeChatId = null,
                chatNotificationsMuted = false
            )
        )
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "sk_dist",
                chatId = "c1",
                activeChatId = null,
                chatNotificationsMuted = null
            )
        )
    }

    @Test
    fun suppressesKnownMutedChat() {
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "c1",
                activeChatId = null,
                chatNotificationsMuted = true
            )
        )
    }

    @Test
    fun allowsUnknownMuteState() {
        assertTrue(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "c1",
                activeChatId = null,
                chatNotificationsMuted = null
            )
        )
    }

    @Test
    fun allowsUnmutedInactiveChat() {
        assertTrue(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "IMAGE",
                chatId = "c1",
                activeChatId = "c2",
                chatNotificationsMuted = false
            )
        )
    }

    @Test
    fun `recipient mismatch drops push`() {
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = "user-a",
                currentUserId = "user-b",
            )
        )
    }

    @Test
    fun `recipient match allows push`() {
        assertTrue(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = "user-a",
                currentUserId = "user-a",
            )
        )
    }

    @Test
    fun `payload without recipientId is rejected`() {
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = null,
                currentUserId = "user-a",
            )
        )
    }

    @Test
    fun `recipient present but no local user drops`() {
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = "user-a",
                currentUserId = null,
            )
        )
    }

    @Test
    fun resolveChatIdDropsBlank() {
        assertNull(PushNotificationPolicy.resolveChatId(null))
        assertNull(PushNotificationPolicy.resolveChatId(""))
        assertNull(PushNotificationPolicy.resolveChatId("  "))
        assertEquals("c1", PushNotificationPolicy.resolveChatId(" c1 "))
    }

    @Test
    fun blankIdsDropAddressing() {
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = "user-a",
                currentUserId = "",
            )
        )
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = " ",
                currentUserId = " ",
            )
        )
    }

    @Test
    fun nullTypeWireIsAllowed() {
        assertTrue(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = null,
                chatId = "c1",
                activeChatId = null,
                chatNotificationsMuted = false
            )
        )
    }

    @Test
    fun whitespaceChatIdIsSuppressed() {
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "   ",
                activeChatId = null,
                chatNotificationsMuted = false
            )
        )
    }

    @Test
    fun blankActiveChatDoesNotSuppress() {
        assertTrue(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "c1",
                activeChatId = "",
                chatNotificationsMuted = false
            )
        )
        assertTrue(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT",
                chatId = "c1",
                activeChatId = " ",
                chatNotificationsMuted = false
            )
        )
    }

    @Test
    fun resolveChatIdTrimsOnlyEdges() {
        assertEquals("a b", PushNotificationPolicy.resolveChatId(" a b "))
        assertEquals("c1", PushNotificationPolicy.resolveChatId("\tc1\n"))
    }
}
