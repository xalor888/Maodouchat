package com.maodouchat.server.plugins

import com.maodouchat.server.model.MessageResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageIdempotencyPolicyTest {

    private val encryptedWireA =
        """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientUserId":"recipient","recipientDeviceId":1,"ciphertextType":"prekey","ciphertext":"Y2lwaGVyLWE="}]}"""
    private val encryptedWireB =
        """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientUserId":"recipient","recipientDeviceId":1,"ciphertextType":"signal","ciphertext":"Y2lwaGVyLWI="}]}"""

    private val existing = MessageResponse(
        id = "m1",
        chatId = "chat-a",
        senderId = "sender-a",
        content = "ciphertext-a",
        type = "TEXT",
        timestamp = 1L,
        sealedSender = true,
    )

    @Test
    fun exactOwnedRetryIsIdempotent() {
        assertTrue(
            isMatchingIdempotentMessageRetry(
                existing = existing,
                senderId = "sender-a",
                chatId = "chat-a",
                content = "ciphertext-a",
                type = "TEXT",
                sealedSender = true,
            )
        )
    }

    @Test
    fun foreignOrChangedRowsMustNotBypassLimiter() {
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing,
                senderId = "sender-b",
                chatId = "chat-a",
                content = "ciphertext-a",
                type = "TEXT",
                sealedSender = true,
            )
        )
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing,
                senderId = "sender-a",
                chatId = "chat-b",
                content = "ciphertext-a",
                type = "TEXT",
                sealedSender = true,
            )
        )
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing,
                senderId = "sender-a",
                chatId = "chat-a",
                content = "ciphertext-b",
                type = "TEXT",
                sealedSender = true,
            )
        )
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing,
                senderId = "sender-a",
                chatId = "chat-a",
                content = "ciphertext-a",
                type = "MARKDOWN",
                sealedSender = true,
            )
        )
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing,
                senderId = "sender-a",
                chatId = "chat-a",
                content = "ciphertext-a",
                type = "TEXT",
                sealedSender = false,
            )
        )
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing = null,
                senderId = "sender-a",
                chatId = "chat-a",
                content = "ciphertext-a",
                type = "TEXT",
                sealedSender = true,
            )
        )
    }

    @Test
    fun reEncryptedWireWithSameLogicalIdentityIsIdempotent() {
        val encryptedExisting = existing.copy(content = encryptedWireA)

        assertTrue(
            isMatchingIdempotentMessageRetry(
                existing = encryptedExisting,
                senderId = encryptedExisting.senderId,
                chatId = encryptedExisting.chatId,
                content = encryptedWireB,
                type = encryptedExisting.type,
                sealedSender = encryptedExisting.sealedSender,
            )
        )
    }

    @Test
    fun singleDevicePreKeyToSignalRetryIsIdempotent() {
        val preKey = """{"version":2,"algorithm":"signal-v2","senderDeviceId":1,"recipientDeviceId":1,"payloadType":"TEXT","ciphertextType":"prekey","ciphertext":"cHJla2V5"}"""
        val signal = """{"version":2,"algorithm":"signal-v2","senderDeviceId":1,"recipientDeviceId":1,"payloadType":"TEXT","ciphertextType":"signal","ciphertext":"c2lnbmFs"}"""

        assertTrue(matchesEncryptedRetry(preKey, signal))
    }

    @Test
    fun compactSingleEnvelopeDefaultsMatchExplicitDefaults() {
        val explicit = """{"version":2,"algorithm":"signal-v2","senderDeviceId":1,"recipientDeviceId":1,"payloadType":"TEXT","ciphertextType":"prekey","ciphertext":"cHJla2V5"}"""
        val compact = """{"payloadType":"TEXT","ciphertext":"c2lnbmFs"}"""

        assertTrue(matchesEncryptedRetry(explicit, compact))
    }

    @Test
    fun quotedNumbersAndInvalidBase64AreRejected() {
        val quotedDevice = encryptedWireB.replace("\"senderDeviceId\":1", "\"senderDeviceId\":\"1\"")
        val invalidBase64 = encryptedWireB.replace("Y2lwaGVyLWI=", "not-base64")

        assertFalse(matchesEncryptedRetry(encryptedWireA, quotedDevice))
        assertFalse(matchesEncryptedRetry(encryptedWireA, invalidBase64))
    }

    @Test
    fun reEncryptedWireWithDifferentRecipientDeviceIsAConflict() {
        val changedTarget = encryptedWireB.replace("\"recipientDeviceId\":1", "\"recipientDeviceId\":2")
        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing = existing.copy(content = encryptedWireA),
                senderId = existing.senderId,
                chatId = existing.chatId,
                content = changedTarget,
                type = existing.type,
                sealedSender = existing.sealedSender,
            )
        )
    }

    @Test
    fun reEncryptedWireWithDifferentPayloadTypeOrSenderDeviceIsAConflict() {
        val changedPayload = encryptedWireB.replace("\"payloadType\":\"TEXT\"", "\"payloadType\":\"IMAGE\"")
        val changedSender = encryptedWireB.replace("\"senderDeviceId\":1", "\"senderDeviceId\":9")
        listOf(changedPayload, changedSender).forEach { retry ->
            assertFalse(
                isMatchingIdempotentMessageRetry(
                    existing = existing.copy(content = encryptedWireA),
                    senderId = existing.senderId,
                    chatId = existing.chatId,
                    content = retry,
                    type = existing.type,
                    sealedSender = existing.sealedSender,
                )
            )
        }
    }

    @Test
    fun changedPlaintextJsonIsStillAConflict() {
        val plaintextJson = existing.copy(
            content = """{"kind":"note","text":"first"}""",
            sealedSender = false,
        )

        assertFalse(
            isMatchingIdempotentMessageRetry(
                existing = plaintextJson,
                senderId = plaintextJson.senderId,
                chatId = plaintextJson.chatId,
                content = """{"kind":"note","text":"second"}""",
                type = plaintextJson.type,
                sealedSender = false,
            )
        )
    }

    private fun matchesEncryptedRetry(first: String, retry: String): Boolean =
        isMatchingIdempotentMessageRetry(
            existing = existing.copy(content = first),
            senderId = existing.senderId,
            chatId = existing.chatId,
            content = retry,
            type = existing.type,
            sealedSender = existing.sealedSender,
        )
}
