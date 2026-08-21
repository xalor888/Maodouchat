package com.maodouchat.server.plugins

import com.maodouchat.server.model.PreKeyData
import com.maodouchat.server.model.UploadKeysRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreKeyUploadValidationTest {

    private val validPreKeys = (1..10).map { PreKeyData(it, "a".repeat(32)) }
    private val validBase64Key = "a".repeat(32)

    @Test
    fun `valid request passes`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = validPreKeys
        )
        assertTrue(req.isValid())
    }

    @Test
    fun `fewer than 10 preKeys fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = (1..5).map { PreKeyData(it, validBase64Key) }
        )
        assertFalse(req.isValid(), "Should reject fewer than 10 preKeys")
    }

    @Test
    fun `preKeyId exceeding Signal protocol max fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = (1..10).map { PreKeyData(16_777_216, validBase64Key) }
        )
        assertFalse(req.isValid(), "Should reject preKeyId > 16777215")
    }

    @Test
    fun `signature shorter than 64 chars fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(32),
            preKeys = validPreKeys
        )
        assertFalse(req.isValid(), "Should reject signature < 64 chars")
    }

    @Test
    fun `signature longer than 512 chars fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(513),
            preKeys = validPreKeys
        )
        assertFalse(req.isValid(), "Should reject signature > 512 chars")
    }

    @Test
    fun `deviceId 0 fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 0,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = validPreKeys
        )
        assertFalse(req.isValid())
    }

    @Test
    fun `deviceId 256 fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 256,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = validPreKeys
        )
        assertFalse(req.isValid())
    }

    @Test
    fun `more than 100 preKeys fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = (1..101).map { PreKeyData(it, validBase64Key) }
        )
        assertFalse(req.isValid())
    }

    @Test
    fun `duplicate preKeyIds fail`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = (1..10).map { PreKeyData(5, validBase64Key) }
        )
        assertFalse(req.isValid())
    }

    @Test
    fun `signedPreKeyId exceeding Signal protocol max fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 16_777_216,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = validPreKeys
        )
        assertFalse(req.isValid(), "Should reject signedPreKeyId > 16777215")
    }

    @Test
    fun `signedPreKeyId zero fails`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            identityKey = validBase64Key,
            signedPreKeyId = 0,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = validPreKeys
        )
        assertFalse(req.isValid())
    }

    @Test
    fun `valid request with device name passes`() {
        val req = UploadKeysRequest(
            registrationId = 12345,
            deviceId = 1,
            deviceName = "My Phone",
            identityKey = validBase64Key,
            signedPreKeyId = 1,
            signedPreKey = validBase64Key,
            signedPreKeySignature = "s".repeat(64),
            preKeys = validPreKeys
        )
        assertTrue(req.isValid())
    }
}
