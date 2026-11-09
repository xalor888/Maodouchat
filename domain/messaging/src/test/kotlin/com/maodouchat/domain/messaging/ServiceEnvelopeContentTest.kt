package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceEnvelopeContentTest {

    private val dataPayload = """{"version":1,"type":"TEXT","body":"hello"}"""

    @Test
    fun `a bot sender on device zero with service plaintext commits`() {
        assertEquals(
            MessagingV2Content(version = 1, type = "TEXT", body = "hello"),
            serviceEnvelopeContent(
                senderUserId = "bot_helper",
                senderDeviceId = 0,
                ciphertextType = "SERVICE_PLAINTEXT",
                kind = "SERVICE",
                ciphertext = dataPayload,
            ),
        )
    }

    @Test
    fun `the system sender is accepted the same way`() {
        assertEquals(
            MessagingV2Content(version = 1, type = "TEXT", body = "hello"),
            serviceEnvelopeContent(
                senderUserId = "system",
                senderDeviceId = 0,
                ciphertextType = "SERVICE_PLAINTEXT",
                kind = "SERVICE",
                ciphertext = dataPayload,
            ),
        )
    }

    @Test
    fun `a nonzero device is not a service sender`() {
        assertNull(
            serviceEnvelopeContent(
                senderUserId = "bot_helper",
                senderDeviceId = 1,
                ciphertextType = "SERVICE_PLAINTEXT",
                kind = "SERVICE",
                ciphertext = dataPayload,
            ),
        )
    }

    @Test
    fun `an ordinary user is not a service sender`() {
        assertNull(
            serviceEnvelopeContent(
                senderUserId = "u2",
                senderDeviceId = 0,
                ciphertextType = "SERVICE_PLAINTEXT",
                kind = "SERVICE",
                ciphertext = dataPayload,
            ),
        )
    }

    @Test
    fun `undecodable ciphertext is dropped`() {
        assertNull(
            serviceEnvelopeContent(
                senderUserId = "bot_helper",
                senderDeviceId = 0,
                ciphertextType = "SERVICE_PLAINTEXT",
                kind = "SERVICE",
                ciphertext = "not-json{{{",
            ),
        )
    }

    @Test
    fun `content failing the content policy for its kind is dropped`() {
        assertNull(
            serviceEnvelopeContent(
                senderUserId = "bot_helper",
                senderDeviceId = 0,
                ciphertextType = "SERVICE_PLAINTEXT",
                kind = "SERVICE",
                ciphertext = """{"version":2,"type":"TEXT","body":"hello"}""",
            ),
        )
    }
}
