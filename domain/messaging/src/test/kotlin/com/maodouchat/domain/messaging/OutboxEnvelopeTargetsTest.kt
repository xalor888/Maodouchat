package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DeviceCiphertext
import com.maodouchat.core.model.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.core.model.MessagingV2DeviceTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxEnvelopeTargetsTest {

    private val targets = linkedSetOf(
        MessagingV2DeviceTarget("bob", 2),
        MessagingV2DeviceTarget("alice", 1),
        MessagingV2DeviceTarget("bob", 1),
    )

    @Test
    fun `target user ids dedupe and keep snapshot order`() {
        assertEquals(listOf("bob", "alice"), targetUserIds(targets).toList())
    }

    @Test
    fun `required recipients exclude the owner`() {
        assertEquals(setOf("alice"), requiredRecipientIds(targetUserIds(targets), "bob"))
        assertEquals(setOf("bob", "alice"), requiredRecipientIds(targetUserIds(targets), "carol"))
    }

    @Test
    fun `device envelopes map fields and uppercase the ciphertext type`() {
        val envelopes = deviceEnvelopes(
            listOf(
                DeviceCiphertext("bob", 2, "prekey", "c1"),
                DeviceCiphertext("alice", 1, "signal", "c2"),
            ),
        )
        assertEquals(
            listOf(
                EncryptedDeviceEnvelopeRequestV2("bob", 2, "PREKEY", "c1"),
                EncryptedDeviceEnvelopeRequestV2("alice", 1, "SIGNAL", "c2"),
            ),
            envelopes,
        )
    }

    @Test
    fun `sender key envelopes sort by user then device and use sender key type`() {
        val envelopes = senderKeyEnvelopes(targets, "sk")
        assertEquals(
            listOf(
                EncryptedDeviceEnvelopeRequestV2("alice", 1, "SENDER_KEY", "sk"),
                EncryptedDeviceEnvelopeRequestV2("bob", 1, "SENDER_KEY", "sk"),
                EncryptedDeviceEnvelopeRequestV2("bob", 2, "SENDER_KEY", "sk"),
            ),
            envelopes,
        )
    }

    @Test
    fun `coverage is order insensitive but rejects missing or extra devices`() {
        val full = senderKeyEnvelopes(targets, "sk")
        assertTrue(envelopeTargetsCovered(full, targets))
        assertTrue(envelopeTargetsCovered(full.reversed(), targets))
        assertFalse(envelopeTargetsCovered(full.drop(1), targets))
        assertFalse(
            envelopeTargetsCovered(
                full + EncryptedDeviceEnvelopeRequestV2("carol", 1, "SENDER_KEY", "sk"),
                targets,
            ),
        )
    }
}
