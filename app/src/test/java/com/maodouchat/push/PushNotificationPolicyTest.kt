package com.maodouchat.push

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
