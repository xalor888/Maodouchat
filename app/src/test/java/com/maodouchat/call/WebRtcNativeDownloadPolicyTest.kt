package com.maodouchat.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebRtcNativeDownloadPolicyTest {

    private val sha = "a".repeat(64)

    @Test
    fun prefersX86OnEmulatorAbiList() {
        assertEquals(
            "x86_64",
            WebRtcNativeDownloadPolicy.requestAbi(arrayOf("x86_64", "arm64-v8a"))
        )
    }

    @Test
    fun prefersArm64OnPhone() {
        assertEquals(
            "arm64-v8a",
            WebRtcNativeDownloadPolicy.requestAbi(arrayOf("arm64-v8a", "armeabi-v7a"))
        )
    }

    @Test
    fun parseChecksumPrefersDedicatedHeader() {
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("\"bbbb\"", sha.uppercase()))
    }

    @Test
    fun parseChecksumStripsWeakEtagQuotes() {
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("W/\"$sha\"", null))
        assertEquals(sha, WebRtcNativeDownloadPolicy.parseChecksum("\"$sha\"", null))
    }

    @Test
    fun parseChecksumRejectsWeakTimeBucketEtag() {
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum("W/\"abcdef0123456789\"", null))
        assertNull(WebRtcNativeDownloadPolicy.parseChecksum(null, null))
    }

    @Test
    fun originTrust() {
        assertTrue(WebRtcNativeDownloadPolicy.isOriginTrusted("https://chat.mdou.me/lib/arm64-v8a"))
        assertTrue(WebRtcNativeDownloadPolicy.isOriginTrusted("https://files.mdou.me/lib/arm64-v8a"))
        assertTrue(WebRtcNativeDownloadPolicy.isOriginTrusted("http://10.0.2.2:8080/lib/arm64-v8a"))
        assertFalse(WebRtcNativeDownloadPolicy.isOriginTrusted("http://cdn.example.com/lib/arm64-v8a"))
        assertFalse(WebRtcNativeDownloadPolicy.isOriginTrusted("https://chat.mdou.me.evil.com/lib"))
        assertFalse(WebRtcNativeDownloadPolicy.isOriginTrusted("ftp://chat.mdou.me/lib"))
        assertFalse(WebRtcNativeDownloadPolicy.isOriginTrusted(""))
    }

    @Test
    fun integrityVerify() {
        assertTrue(WebRtcNativeDownloadPolicy.verifyIntegrity(sha, sha.uppercase()))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(sha, "b".repeat(64)))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity("", sha))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity("abc", sha))
        assertFalse(WebRtcNativeDownloadPolicy.verifyIntegrity(sha, null))
    }
}
