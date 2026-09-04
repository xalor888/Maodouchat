package com.maodouchat.server.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdatePublishPolicyTest {

    @Test
    fun tokenMustBeLongEnoughAndConstantTimeEqual() {
        assertFalse(AppUpdatePublishPolicy.tokenConfigured(""))
        assertFalse(AppUpdatePublishPolicy.tokenConfigured("short"))
        assertTrue(AppUpdatePublishPolicy.tokenConfigured("sixteen-chars-ok"))
        assertTrue(AppUpdatePublishPolicy.tokensMatch("sixteen-chars-ok", "sixteen-chars-ok"))
        assertFalse(AppUpdatePublishPolicy.tokensMatch("sixteen-chars-ok", "sixteen-chars-no"))
        assertFalse(AppUpdatePublishPolicy.tokensMatch("sixteen-chars-ok", "Bearer sixteen-chars-ok"))
    }

    @Test
    fun versionAndNotesParsing() {
        assertEquals(18, AppUpdatePublishPolicy.parseVersionCode("18"))
        assertNull(AppUpdatePublishPolicy.parseVersionCode("0"))
        assertNull(AppUpdatePublishPolicy.parseVersionCode("-1"))
        assertEquals("1.2.3", AppUpdatePublishPolicy.parseVersionName("1.2.3"))
        assertNull(AppUpdatePublishPolicy.parseVersionName("has space"))
        assertEquals("abc", AppUpdatePublishPolicy.sanitizeNotes("  abc  "))
    }

    @Test
    fun `downgrade and equal version are rejected`() {
        assertTrue(AppUpdatePublishPolicy.isDowngrade(5, 5), "equal version is a downgrade")
        assertTrue(AppUpdatePublishPolicy.isDowngrade(4, 5), "lower version is a downgrade")
        assertFalse(AppUpdatePublishPolicy.isDowngrade(6, 5), "higher version is allowed")
        assertFalse(AppUpdatePublishPolicy.isDowngrade(1, 0), "first publish (current 0) is allowed")
    }

    @Test
    fun zipMagicAndPublicUrl() {
        assertTrue(AppUpdatePublishPolicy.isZipMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(AppUpdatePublishPolicy.isZipMagic(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        assertEquals(
            "https://chat.example.com/api/public/app-update/latest.apk",
            AppUpdatePublishPolicy.publicApkUrl("https://chat.example.com/"),
        )
        assertEquals("secret", AppUpdatePublishPolicy.bearerToken("Bearer secret"))
        assertNull(AppUpdatePublishPolicy.bearerToken("secret"))
    }
}
