package com.maodouchat.server.service

import com.maodouchat.server.model.NotificationSettingsResponse
import com.maodouchat.server.repository.NotificationPreferenceRepository
import com.maodouchat.server.repository.PushTokenRepository
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FcmPushServiceTest {

    @Test
    fun `permanent token failures cover unregistered not found and sender mismatch`() {
        assertTrue(
            FcmPushService.isPermanentTokenFailure(
                404,
                """{"error":{"status":"NOT_FOUND","message":"Requested entity was not found."}}"""
            )
        )
        assertTrue(
            FcmPushService.isPermanentTokenFailure(
                404,
                """{"error":{"status":"UNREGISTERED","details":[{"errorCode":"UNREGISTERED"}]}}"""
            )
        )
        assertTrue(
            FcmPushService.isPermanentTokenFailure(
                400,
                """{"error":{"status":"INVALID_ARGUMENT","message":"The registration token is not a valid FCM registration token"}}"""
            )
        )
        assertTrue(
            FcmPushService.isPermanentTokenFailure(
                400,
                """{"error":{"status":"SENDER_ID_MISMATCH"}}"""
            )
        )
        assertTrue(
            FcmPushService.isPermanentTokenFailure(
                400,
                "registration-token-not-registered"
            )
        )
    }

    @Test
    fun `payload encoding invalid argument does not look like a dead token`() {
        assertFalse(
            FcmPushService.isPermanentTokenFailure(
                400,
                """{"error":{"status":"INVALID_ARGUMENT","message":"Invalid JSON payload received. Unknown name ttl at android"}}"""
            )
        )
        assertFalse(FcmPushService.isPermanentTokenFailure(401, "UNREGISTERED"))
        assertFalse(FcmPushService.isPermanentTokenFailure(429, "UNREGISTERED"))
        assertFalse(FcmPushService.isPermanentTokenFailure(500, "NOT_FOUND"))
    }

    @Test
    fun `transient http failures are 429 and 5xx`() {
        assertTrue(FcmPushService.isTransientHttpFailure(429))
        assertTrue(FcmPushService.isTransientHttpFailure(500))
        assertTrue(FcmPushService.isTransientHttpFailure(503))
        assertFalse(FcmPushService.isTransientHttpFailure(400))
        assertFalse(FcmPushService.isTransientHttpFailure(401))
        assertFalse(FcmPushService.isTransientHttpFailure(404))
        assertFalse(FcmPushService.isTransientHttpFailure(200))
    }

    @Test
    fun `incoming call uses short ttl other types keep 24h`() {
        assertEquals("60s", FcmPushService.ttlFor("INCOMING_CALL"))
        assertEquals("86400s", FcmPushService.ttlFor("NEW_MESSAGE"))
        assertEquals("86400s", FcmPushService.ttlFor("GROUP_INVITE"))
        assertEquals("86400s", FcmPushService.ttlFor(null))
    }

    @Test
    fun `overnight dnd is quiet at local 00 30 and open at 12 00`() {
        val settings = NotificationSettingsResponse(
            dndEnabled = true,
            dndStartMinute = 22 * 60,
            dndEndMinute = 7 * 60
        )
        val midnightUtc = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
        val noonUtc = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        assertTrue(FcmPushService.isInDoNotDisturb(settings, offsetMinutes = 0, nowMillis = midnightUtc))
        assertFalse(FcmPushService.isInDoNotDisturb(settings, offsetMinutes = 0, nowMillis = noonUtc))
        // UTC+8 08:30 is still inside 22:00–07:00 overnight window? 00:30 UTC + 480 = 08:30 local → open
        assertFalse(FcmPushService.isInDoNotDisturb(settings, offsetMinutes = 8 * 60, nowMillis = midnightUtc))
    }

    @Test
    fun `same start and end minute or disabled dnd never quiets`() {
        val now = Instant.parse("2026-01-01T23:00:00Z").toEpochMilli()
        assertFalse(
            FcmPushService.isInDoNotDisturb(
                NotificationSettingsResponse(dndEnabled = true, dndStartMinute = 100, dndEndMinute = 100),
                offsetMinutes = 0,
                nowMillis = now
            )
        )
        assertFalse(
            FcmPushService.isInDoNotDisturb(
                NotificationSettingsResponse(dndEnabled = false, dndStartMinute = 22 * 60, dndEndMinute = 7 * 60),
                offsetMinutes = 0,
                nowMillis = now
            )
        )
    }

    @Test
    fun `same-day dnd window uses half-open local minutes`() {
        val settings = NotificationSettingsResponse(
            dndEnabled = true,
            dndStartMinute = 9 * 60,
            dndEndMinute = 17 * 60
        )
        val nineUtc = Instant.parse("2026-01-01T09:00:00Z").toEpochMilli()
        val almostEnd = Instant.parse("2026-01-01T16:59:00Z").toEpochMilli()
        val end = Instant.parse("2026-01-01T17:00:00Z").toEpochMilli()
        assertTrue(FcmPushService.isInDoNotDisturb(settings, 0, nineUtc))
        assertTrue(FcmPushService.isInDoNotDisturb(settings, 0, almostEnd))
        assertFalse(FcmPushService.isInDoNotDisturb(settings, 0, end))
        val localMinute = Instant.ofEpochMilli(nineUtc).atOffset(ZoneOffset.UTC).let { it.hour * 60 + it.minute }
        assertEquals(9 * 60, localMinute)
    }

    @Test
    fun `signPayload sorts keys and hmac matches client canonical form`() {
        val data = mapOf(
            "type" to "NEW_MESSAGE",
            "chatId" to "c1",
            "messageId" to "m1",
            "senderId" to "u1",
            "recipientId" to "u2"
        )
        val signed = FcmPushService.signPayload("u2", data)
        assertTrue(signed["ts"].orEmpty().isNotBlank())
        assertTrue(signed["sig"].orEmpty().matches(Regex("[0-9a-f]{64}")))
        val ts = signed.getValue("ts")
        val canonical = data.keys.sorted().joinToString("&") { "$it=${data[it]}" }
        val expected = hmacHex(FcmPushService.pushKeyForUser("u2"), "$canonical&ts=$ts")
        assertTrue(MessageDigest.isEqual(expected.toByteArray(), signed.getValue("sig").toByteArray()))
        assertEquals("NEW_MESSAGE", signed["type"])
        assertFalse("ciphertext" in signed.values.joinToString())
    }

    @Test
    fun `enqueue drops blank ids self send and empty recipient lists`() {
        withConfiguredService { service, queued ->
            service.enqueueEncryptedMessage(
                recipientIds = listOf("u1", "", "u1", "u2"),
                chatId = "c1",
                messageId = "m1",
                senderId = "u1",
                messageType = "TEXT"
            )
            assertEquals(1, queued.size)
            assertEquals("u2", queued.single().recipientId)
            assertEquals("NEW_MESSAGE", queued.single().data["type"])
            assertEquals("c1", queued.single().data["chatId"])
            queued.clear()

            service.enqueueEncryptedMessage(listOf("u2"), chatId = " ", messageId = "m1", senderId = "u1", messageType = "TEXT")
            service.enqueueEncryptedMessage(listOf("u2"), chatId = "c1", messageId = "", senderId = "u1", messageType = "TEXT")
            service.enqueueEncryptedMessage(emptyList(), chatId = "c1", messageId = "m1", senderId = "u1", messageType = "TEXT")
            assertTrue(queued.isEmpty())

            service.enqueueIncomingCall(recipientId = "", senderId = "u1", isVideo = false, callId = "call-1")
            service.enqueueIncomingCall(recipientId = "u1", senderId = "u1", isVideo = true)
            assertTrue(queued.isEmpty())

            service.enqueueIncomingCall(recipientId = "u2", senderId = "u1", isVideo = true, callId = "call-9")
            assertEquals("INCOMING_CALL", queued.single().data["type"])
            assertEquals("VIDEO", queued.single().data["callType"])
            assertEquals("call-9", queued.single().data["callId"])
            assertTrue(queued.single().isCall)
            queued.clear()

            service.enqueuePostInteraction("u2", "u1", postId = "", interaction = "LIKE")
            service.enqueuePostInteraction("u2", "u1", postId = "p1", interaction = "UNKNOWN")
            service.enqueueFriendRequest("u2", "u1", requestId = "", action = "CREATED")
            service.enqueueGroupInvite("u2", "u1", inviteId = "", chatId = "g1", action = "CREATED")
            service.enqueueGroupInvite("u2", "u1", inviteId = "inv1", chatId = "g1", action = "ACCEPTED")
            assertTrue(queued.isEmpty())

            service.enqueueAnnouncement("u2", "a1", title = "  hello world  ", level = "EMERGENCY")
            assertEquals(1, queued.size)
            assertTrue(queued.single().breakthroughDnd)
            assertEquals("hello world", queued.single().data["title"])
            queued.clear()

            val longTitle = "x".repeat(120)
            service.enqueueAnnouncement("u2", "a2", title = longTitle, level = "MAINTENANCE")
            assertEquals(80, queued.single().data["title"]?.length)
            assertFalse(queued.single().breakthroughDnd)
        }
    }

    @Test
    fun `FCM transport is disabled and isConfigured is always false`() {
        val queued = mutableListOf<FcmPushService.Delivery>()
        val service = FcmPushService(
            pushTokenRepository = PushTokenRepository(),
            preferenceRepository = NotificationPreferenceRepository(),
            projectId = "ignored",
            serviceAccountFile = "/tmp/does-not-matter.json",
            startWorkers = false
        )
        service.onQueued = { queued += it }
        try {
            assertFalse(service.isConfigured)
            service.enqueueEncryptedMessage(listOf("u2"), "c1", "m1", "u1", "TEXT")
            assertEquals(1, queued.size)
            assertEquals("NEW_MESSAGE", queued.single().data["type"])
        } finally {
            service.shutdown()
        }
    }

    private fun hmacHex(key: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun withConfiguredService(block: (FcmPushService, MutableList<FcmPushService.Delivery>) -> Unit) {
        val queued = mutableListOf<FcmPushService.Delivery>()
        val service = FcmPushService(
            pushTokenRepository = PushTokenRepository(),
            preferenceRepository = NotificationPreferenceRepository(),
            startWorkers = false
        )
        service.onQueued = { queued += it }
        try {
            assertFalse(service.isConfigured)
            block(service, queued)
        } finally {
            service.shutdown()
        }
    }
}
