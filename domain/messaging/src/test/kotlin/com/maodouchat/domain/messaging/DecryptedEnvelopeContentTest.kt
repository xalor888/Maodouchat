package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecryptedEnvelopeContentTest {

    private val dataPayload = """{"version":1,"type":"TEXT","body":"hello"}"""

    @Test
    fun `a matching data payload commits`() {
        assertEquals(
            MessagingV2Content(version = 1, type = "TEXT", body = "hello"),
            decryptedEnvelopeContent("DATA", dataPayload),
        )
    }

    @Test
    fun `a valid event payload commits under the event kind`() {
        val eventPayload = """{"version":1,"type":"EVENT","event":{"action":"DELETE","targetMessageId":"m1"}}"""
        val content = decryptedEnvelopeContent("EVENT", eventPayload)
        assertEquals("EVENT", content?.type)
        assertEquals("DELETE", content?.event?.action)
    }

    @Test
    fun `a kind disguised as a receipt does not commit a data payload`() {
        assertNull(decryptedEnvelopeContent("RECEIPT", dataPayload))
    }

    @Test
    fun `undecodable plaintext does not commit`() {
        assertNull(decryptedEnvelopeContent("DATA", "not-json{{{"))
    }

    @Test
    fun `an unsupported content version does not commit`() {
        assertNull(
            decryptedEnvelopeContent(
                "DATA",
                """{"version":3,"type":"TEXT","body":"hello"}""",
            ),
        )
    }
}
