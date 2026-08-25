package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagePayloadValidationTest {

    @Test
    fun oversizedPlainTextAndMarkdownAreRejected() {
        val oversizedPlaintext = "p".repeat(MAX_TEXT_CONTENT_LENGTH + 1)

        assertFalse(isValidMessagePayload(oversizedPlaintext, "TEXT", "m_plain_text"))
        assertFalse(isValidMessagePayload(oversizedPlaintext, "MARKDOWN", "m_plain_markdown"))
    }

    @Test
    fun directMultiDeviceTextWireAboveLegacyLimitIsAccepted() {
        val ciphertext = "a".repeat(10_000)
        val entries = (1..2).joinToString(",") { deviceId ->
            """{"recipientUserId":"u2","recipientDeviceId":$deviceId,"ciphertextType":"prekey","ciphertext":"$ciphertext"}"""
        }
        val wire =
            """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[$entries]}"""

        assertTrue(wire.length > 16_384)
        assertTrue(wire.length <= MAX_TEXT_WIRE_CONTENT_LENGTH)
        assertTrue(isValidMessagePayload(wire, "TEXT", "m_direct", requireGroupSenderKey = false))
        val markdownWire = wire.replace("\"payloadType\":\"TEXT\"", "\"payloadType\":\"MARKDOWN\"")
        assertTrue(isValidMessagePayload(markdownWire, "MARKDOWN", "m_markdown", requireGroupSenderKey = false))
    }

    @Test
    fun textWireAboveControlledLimitIsRejected() {
        val ciphertext = "a".repeat(MAX_TEXT_WIRE_CONTENT_LENGTH)
        val wire =
            """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientUserId":"u2","recipientDeviceId":1,"ciphertext":"$ciphertext"}]}"""

        assertTrue(wire.length > MAX_TEXT_WIRE_CONTENT_LENGTH)
        assertFalse(isValidMessagePayload(wire, "TEXT", "m_oversized", requireGroupSenderKey = false))
    }

    @Test
    fun groupSenderKeyTextWireAboveLegacyLimitIsAccepted() {
        val ciphertext = "a".repeat(20_000)
        val wire =
            """{"version":1,"algorithm":"signal-sender-key-v1","groupId":"g1","epoch":1,"senderDeviceId":1,"distributionId":"d1","payloadType":"TEXT","ciphertext":"$ciphertext"}"""

        assertTrue(wire.length > 16_384)
        assertTrue(isValidMessagePayload(wire, "TEXT", "m_group", requireGroupSenderKey = true))
    }

    @Test
    fun malformedFakeWireDoesNotReceiveTheLargerLimit() {
        val padding = "x".repeat(MAX_TEXT_CONTENT_LENGTH + 1)
        val missingEntries =
            """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","padding":"$padding"}"""
        val malformedEntry =
            """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientDeviceId":1,"ciphertext":"not-base64!","padding":"$padding"}]}"""

        assertTrue(missingEntries.length > MAX_TEXT_CONTENT_LENGTH)
        assertFalse(isValidMessagePayload(missingEntries, "TEXT", "m_fake_missing"))
        assertTrue(malformedEntry.length > MAX_TEXT_CONTENT_LENGTH)
        assertFalse(isValidMessagePayload(malformedEntry, "TEXT", "m_fake_entry"))
    }

    @Test
    fun compactHistoricalWireUsesProtocolDefaults() {
        val ciphertext = "a".repeat(MAX_TEXT_CONTENT_LENGTH + 4)
        // encodeDefaults=false historical multi-device wire: algorithm, version,
        // senderDeviceId and the single entry's recipientDeviceId are omitted.
        val compactMulti =
            """{"payloadType":"TEXT","entries":[{"ciphertext":"$ciphertext"}]}"""
        // The old single-device wire may omit the same envelope defaults and its
        // nullable recipientDeviceId.
        val compactSingle =
            """{"payloadType":"TEXT","ciphertext":"$ciphertext"}"""

        assertTrue(compactMulti.length > MAX_TEXT_CONTENT_LENGTH)
        assertTrue(isValidMessagePayload(compactMulti, "TEXT", "m_compact_multi"))
        assertTrue(compactSingle.length > MAX_TEXT_CONTENT_LENGTH)
        assertTrue(isValidMessagePayload(compactSingle, "TEXT", "m_compact_single"))
    }

    @Test
    fun multiDeviceWireRequiresRecipientForMultipleEntries() {
        val ciphertext = "a".repeat(MAX_TEXT_CONTENT_LENGTH + 4)
        val missingDeviceId =
            """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"ciphertext":"$ciphertext"},{"recipientDeviceId":2,"ciphertext":"$ciphertext"}]}"""
        val stringDeviceId =
            """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientDeviceId":"1","ciphertext":"$ciphertext"}]}"""
        val singleMissingDeviceId =
            """{"version":2,"algorithm":"signal-v2","senderDeviceId":1,"payloadType":"TEXT","ciphertext":"$ciphertext"}"""
        val singleStringDeviceId =
            """{"version":2,"algorithm":"signal-v2","senderDeviceId":1,"recipientDeviceId":"1","payloadType":"TEXT","ciphertext":"$ciphertext"}"""

        assertTrue(missingDeviceId.length > MAX_TEXT_CONTENT_LENGTH)
        assertFalse(isValidMessagePayload(missingDeviceId, "TEXT", "m_missing_device"))
        assertTrue(stringDeviceId.length > MAX_TEXT_CONTENT_LENGTH)
        assertFalse(isValidMessagePayload(stringDeviceId, "TEXT", "m_string_device"))
        assertTrue(singleMissingDeviceId.length > MAX_TEXT_CONTENT_LENGTH)
        assertTrue(isValidMessagePayload(singleMissingDeviceId, "TEXT", "m_single_missing_device"))
        assertTrue(singleStringDeviceId.length > MAX_TEXT_CONTENT_LENGTH)
        assertFalse(isValidMessagePayload(singleStringDeviceId, "TEXT", "m_single_string_device"))
    }
}
