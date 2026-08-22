package com.maodouchat.server.service

import com.maodouchat.server.model.MessageResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SealedSenderDeliveryTest {

    private inline fun <T> withJwtSecret(secret: String?, block: () -> T): T {
        val old = System.getProperty("JWT_SECRET")
        try {
            if (secret != null) {
                System.setProperty("JWT_SECRET", secret)
            } else {
                System.clearProperty("JWT_SECRET")
            }
            return block()
        } finally {
            if (old != null) {
                System.setProperty("JWT_SECRET", old)
            } else {
                System.clearProperty("JWT_SECRET")
            }
        }
    }

    @Test
    fun `chat history keeps real sender id`() {
        val message = MessageResponse(
            id = "m1",
            chatId = "c1",
            senderId = "u1001",
            content = "ciphertext",
            timestamp = 1L,
            sealedSender = true
        )
        val viewed = SealedSenderDelivery.forViewer(message, "u2002")
        assertEquals("u1001", viewed.senderId)
        assertTrue(viewed.sealedSender)
    }

    @Test
    fun `push and webhook redact when sealed`() {
        assertEquals("sealed", SealedSenderDelivery.pushSenderId("u1001", sealed = true))
        assertEquals("u1001", SealedSenderDelivery.pushSenderId("u1001", sealed = false))
        assertEquals("sealed", SealedSenderDelivery.webhookSenderId("u1001", sealed = true))
        assertEquals("u1001", SealedSenderDelivery.webhookSenderId("u1001", sealed = false))
        assertEquals(null, SealedSenderDelivery.webhookSenderId(null, sealed = true))
        assertEquals("", SealedSenderDelivery.webhookSenderId("", sealed = true))
    }

    @Test
    fun `authorize requires matching cert and optional device bind`() {
        withJwtSecret("test-jwt-secret-at-least-32-chars-long-abc") {
            assertFalse(
                SealedSenderDelivery.authorize(
                    requested = false,
                    certificateHeader = "ignored",
                    certificateBody = null,
                    userId = "u1001"
                )
            )
            assertFalse(
                SealedSenderDelivery.authorize(
                    requested = true,
                    certificateHeader = null,
                    certificateBody = "   ",
                    userId = "u1001"
                )
            )

            val issued = SealedSenderCertificateService.issue("u1001", 2)
            assertNotNull(issued)

            assertTrue(
                SealedSenderDelivery.authorize(
                    requested = true,
                    certificateHeader = issued.certificate,
                    certificateBody = null,
                    userId = "u1001"
                )
            )
            assertTrue(
                SealedSenderDelivery.authorize(
                    requested = true,
                    certificateHeader = null,
                    certificateBody = issued.certificate,
                    userId = "u1001",
                    deviceId = 2
                )
            )
            assertFalse(
                SealedSenderDelivery.authorize(
                    requested = true,
                    certificateHeader = issued.certificate,
                    certificateBody = null,
                    userId = "u1001",
                    deviceId = 9
                )
            )
            assertFalse(
                SealedSenderDelivery.authorize(
                    requested = true,
                    certificateHeader = issued.certificate,
                    certificateBody = null,
                    userId = "u9999"
                )
            )
        }
    }

    @Test
    fun `authorize fails closed when jwt secret is blank`() {
        val issued = withJwtSecret("test-jwt-secret-at-least-32-chars-long-abc") {
            SealedSenderCertificateService.issue("u1001", 1)
        }
        assertNotNull(issued)
        withJwtSecret("") {
            assertFalse(
                SealedSenderDelivery.authorize(
                    requested = true,
                    certificateHeader = issued.certificate,
                    certificateBody = null,
                    userId = "u1001"
                )
            )
        }
    }
}
