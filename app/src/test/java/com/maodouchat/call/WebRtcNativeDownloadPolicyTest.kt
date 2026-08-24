package com.maodouchat.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
