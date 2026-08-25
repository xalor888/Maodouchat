package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupSenderKeyPayloadTest {

    @Test
    fun groupHumanTrafficMustBeSenderKeyEnvelope() {
        val sk = """{"version":1,"algorithm":"signal-sender-key-v1","groupId":"g1","epoch":0,"senderDeviceId":1,"distributionId":"d","payloadType":"TEXT","ciphertext":"abc"}"""
        val skd = """{"version":1,"algorithm":"signal-sender-key-distribution-v1","groupId":"g1","epoch":0,"senderDeviceId":1,"distributionId":"d","distributionMessage":"skdm"}"""
        assertTrue(isValidMessagePayload(sk, "TEXT", null, requireGroupSenderKey = true))
        assertTrue(isValidMessagePayload(skd, "SK_DIST", "m1", requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload("ciphertext", "TEXT", null, requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload("hello", "TEXT", null, requireGroupSenderKey = true))
        assertTrue(isValidMessagePayload("hello", "TEXT", null, requireGroupSenderKey = false))
    }
}
