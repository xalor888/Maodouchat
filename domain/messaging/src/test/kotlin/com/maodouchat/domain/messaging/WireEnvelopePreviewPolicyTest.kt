package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireEnvelopePreviewPolicyTest {

    @Test
    fun shouldKeepExistingOwnPreviewOnlyForSameMessageReadableBody() {
        // Room plaintext for this message id (local send echo).
        assertTrue(
            WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "hello",
                encryptedPlaceholder = "[enc]"
            )
        )
        assertTrue(
            WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.IMAGE,
                existingSameMessageContent = "blob-or-meta",
                encryptedPlaceholder = "[enc]"
            )
        )
        // Previous chat-list tail alone is not enough — multi-device new send must not freeze.
        assertFalse(
            WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = null,
                encryptedPlaceholder = "[enc]"
            )
        )
        assertFalse(
            WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = false,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "hello",
                encryptedPlaceholder = "[enc]"
            )
        )
        assertFalse(
            WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "{\"type\":\"ciphertext\"}",
                encryptedPlaceholder = "[enc]"
            )
        )
        assertFalse(
            WireEnvelopePreviewPolicy.shouldKeepExistingOwnPreview(
                isOwnMessage = true,
                messageType = MessageType.TEXT,
                existingSameMessageContent = "[enc]",
                encryptedPlaceholder = "[enc]"
            )
        )
    }

    @Test
    fun textPreviewFromPlainOrEncryptedNeverSurfacesWire() {
        assertEquals(
            "hi there",
            WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("hi there", "[enc]")
        )
        assertEquals(
            "[enc]",
            WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("{\"v\":1}", "[enc]")
        )
        assertEquals(
            "[enc]",
            WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted(null, "[enc]")
        )
        assertEquals(
            "[enc]",
            WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("  ", "[enc]")
        )
        assertEquals(
            "a".repeat(200),
            WireEnvelopePreviewPolicy.textPreviewFromPlainOrEncrypted("a".repeat(250), "[enc]")
        )
    }

    @Test
    fun looksLikeWireEnvelopeDoesNotTreatMediaLabelsAsJson() {
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("{\"type\":\"ciphertext\"}"))
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[{\"deviceId\":1}]"))
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[\"entry\"]"))
        assertFalse(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[图片]"))
        assertFalse(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[GIF]"))
        assertFalse(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[Image]"))
        assertFalse(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("hello"))
        assertFalse(WireEnvelopePreviewPolicy.looksLikeWireEnvelope(""))
    }

    @Test
    fun redactedIfWireHidesEnvelopeButKeepsPlaintext() {
        val wire = "{\"senderDeviceId\":76,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertextType\":\"prekey\",\"ciphertext\":\"NAgB\"}]}"
        assertEquals("[enc]", WireEnvelopePreviewPolicy.redactedIfWire(wire, "[enc]"))
        assertEquals("hello", WireEnvelopePreviewPolicy.redactedIfWire("hello", "[enc]"))
        assertEquals("[enc]", WireEnvelopePreviewPolicy.redactedIfWire("  ", "[enc]"))
    }

    // 从 SignalWireEnvelopePreviewTest 拆出的断言：严格判定 isSignalWireEnvelope 留守 core，
    // 此处只覆盖宽松判定与脱敏。
    @Test
    fun signalOneToOneEnvelopeIsLooseWire() {
        val body = "{\"version\":2,\"algorithm\":\"signal-v2\",\"ciphertext\":\"abc\"}"
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope(body))
    }

    @Test
    fun userJsonStartingWithBraceIsLooseWire() {
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("{\"hello\":\"world\"}"))
    }

    @Test
    fun arrayEnvelopeWithoutSignalMarkerIsLooseWire() {
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope("[{\"deviceId\":1}]"))
    }

    @Test
    fun multiDeviceEnvelopeWithoutAlgorithmIsLooseWire() {
        val body =
            "{\"senderDeviceId\":76,\"payloadType\":\"TEXT\",\"entries\":[{\"recipientUserId\":\"u2\",\"recipientDeviceId\":188,\"ciphertextType\":\"prekey\",\"ciphertext\":\"NAgB\"}]}"
        assertTrue(WireEnvelopePreviewPolicy.looksLikeWireEnvelope(body))
    }

    @Test
    fun compactEnvelopeIsRedactedButUserJsonIsNot() {
        val body =
            "{\"senderDeviceId\":76,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertextType\":\"prekey\",\"ciphertext\":\"NAgB\"}]}"
        assertEquals("[encrypted]", WireEnvelopePreviewPolicy.redactedIfWire(body, "[encrypted]"))
        assertEquals("hello", WireEnvelopePreviewPolicy.redactedIfWire("hello", "[encrypted]"))
        assertEquals("{\"hello\":\"world\"}", WireEnvelopePreviewPolicy.redactedIfWire("{\"hello\":\"world\"}", "[encrypted]"))
    }
}
