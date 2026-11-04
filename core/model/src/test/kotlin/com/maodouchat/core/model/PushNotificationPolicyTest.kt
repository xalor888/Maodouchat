package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationPolicyTest {

    @Test
    fun `wake for another account is dropped`() {
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = "other", currentUserId = "me",
            ),
        )
        assertFalse(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = null, currentUserId = "me",
            ),
        )
        assertTrue(
            PushNotificationPolicy.isAddressedToCurrentUser(
                payloadRecipientId = "me", currentUserId = "me",
            ),
        )
    }

    @Test
    fun `muted or active chat does not surface`() {
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT", chatId = "c1",
                activeChatId = "c1", chatNotificationsMuted = false,
            ),
        )
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT", chatId = "c1",
                activeChatId = null, chatNotificationsMuted = true,
            ),
        )
        assertFalse(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "SK_DIST", chatId = "c1",
                activeChatId = null, chatNotificationsMuted = false,
            ),
        )
        assertTrue(
            PushNotificationPolicy.shouldShowNewMessage(
                messageTypeWire = "TEXT", chatId = "c1",
                activeChatId = null, chatNotificationsMuted = null,
            ),
        )
        assertNull(PushNotificationPolicy.resolveChatId("   "))
        assertEquals("c1", PushNotificationPolicy.resolveChatId(" c1 "))
    }
}
