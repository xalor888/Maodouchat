package com.maodouchat.server.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupSenderKeyPayloadTest {

    @Test
    fun groupHumanTrafficMustBeSenderKeyEnvelope() {
        val sk = """{"version":1,"algorithm":"signal-sender-key-v1","groupId":"g1","epoch":0,"senderDeviceId":1,"distributionId":"d","payloadType":"TEXT","ciphertext":"abc"}"""
        val skd = """{"version":1,"algorithm":"signal-sender-key-distribution-v1","groupId":"g1","epoch":0,"senderDeviceId":1,"distributionId":"d","distributionMessage":"skdm"}"""
        val encryptedSkd = """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"SK_DIST","entries":[{"recipientUserId":"u2","recipientDeviceId":2,"ciphertextType":"prekey","ciphertext":"abc"}]}"""
        val image = sk.replace("\"payloadType\":\"TEXT\"", "\"payloadType\":\"IMAGE\"")
        assertTrue(isValidMessagePayload(sk, "TEXT", null, requireGroupSenderKey = true))
        assertTrue(isValidMessagePayload(image, "IMAGE", "image_1", requireGroupSenderKey = true))
        assertTrue(isValidMessagePayload(skd, "SK_DIST", "m1", requireGroupSenderKey = true))
        assertTrue(isValidMessagePayload(encryptedSkd, "SK_DIST", "m2", requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload("ciphertext", "TEXT", null, requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload("hello", "TEXT", null, requireGroupSenderKey = true))
        assertTrue(isValidMessagePayload("hello", "TEXT", null, requireGroupSenderKey = false))
    }

    @Test
    fun encryptedSenderKeyDistributionMustDeclareItsControlPayload() {
        val validControl = """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"SK_DIST","entries":[{"recipientUserId":"u2","recipientDeviceId":2,"ciphertext":"abc"}]}"""
        val wrongType = """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"TEXT","entries":[{"recipientUserId":"u2","recipientDeviceId":2,"ciphertext":"abc"}]}"""
        val noTargets = """{"version":3,"algorithm":"signal-multi-device-v1","senderDeviceId":1,"payloadType":"SK_DIST","entries":[]}"""
        val incidentalAlgorithm = """{"note":"signal-sender-key-distribution-v1"}"""

        assertFalse(isValidMessagePayload(validControl, "TEXT", "m0", requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload(wrongType, "SK_DIST", "m1", requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload(noTargets, "SK_DIST", "m2", requireGroupSenderKey = true))
        assertFalse(isValidMessagePayload(incidentalAlgorithm, "SK_DIST", "m3", requireGroupSenderKey = true))
    }
}
