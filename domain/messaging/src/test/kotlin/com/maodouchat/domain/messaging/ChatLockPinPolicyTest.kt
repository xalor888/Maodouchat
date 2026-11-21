package com.maodouchat.domain.messaging

import com.maodouchat.core.session.VerificationOutcome
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatLockPinPolicyTest {

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `settable pin is 4 to 8 chars regardless of digits`() {
        assertFalse(ChatLockPinPolicy.isSettablePin(""))
        assertFalse(ChatLockPinPolicy.isSettablePin("123"))
        assertTrue(ChatLockPinPolicy.isSettablePin("1234"))
        assertTrue(ChatLockPinPolicy.isSettablePin("12345678"))
        assertFalse(ChatLockPinPolicy.isSettablePin("123456789"))
        // 长度-only：生产入口的数字门在 ChatPinUnlockPolicy.isValidPin，repo 层不限数字。
        assertTrue(ChatLockPinPolicy.isSettablePin("abcd"))
    }

    @Test
    fun `encodeNewLock produces pbkdf2 600k format`() {
        val stored = ChatLockPinPolicy.encodeNewLock("1234")
        assertTrue(stored.formattedString.startsWith("pbkdf2\$600000\$"))
        val parts = stored.formattedString.split("$")
        assertEquals(4, parts.size)
        assertEquals(32, parts[2].length, "salt 应为 16 字节 hex = 32 字符")
        assertEquals(64, parts[3].length, "hash 应为 256 位 hex = 64 字符")
        assertEquals(600_000, stored.iterations)
        assertTrue(stored.isPbkdf2)
    }

    @Test
    fun `encodeNewLock salts differ across calls`() {
        val first = ChatLockPinPolicy.encodeNewLock("1234")
        val second = ChatLockPinPolicy.encodeNewLock("1234")
        assertNotEquals(first.saltHex, second.saltHex)
        assertNotEquals(first.formattedString, second.formattedString)
    }

    @Test
    fun `verifyStoredPin round trips an encoded lock`() {
        val stored = ChatLockPinPolicy.encodeNewLock("1234")
        val outcome = ChatLockPinPolicy.verifyStoredPin("1234", stored.formattedString, stored.saltHex)
        assertTrue(outcome is VerificationOutcome.Success)
        outcome as VerificationOutcome.Success
        assertFalse(outcome.needsUpgrade)
        assertNull(outcome.upgradedHash)
    }

    @Test
    fun `verifyStoredPin rejects a wrong pin`() {
        val stored = ChatLockPinPolicy.encodeNewLock("1234")
        assertEquals(
            VerificationOutcome.Mismatch,
            ChatLockPinPolicy.verifyStoredPin("1235", stored.formattedString, stored.saltHex),
        )
    }

    @Test
    fun `verifyStoredPin upgrades a legacy sha256 hash`() {
        val salt = "aabbccddeeff00112233445566778899"
        val legacyHash = sha256Hex("1234" + salt)
        val outcome = ChatLockPinPolicy.verifyStoredPin("1234", legacyHash, salt)
        assertTrue(outcome is VerificationOutcome.Success)
        outcome as VerificationOutcome.Success
        assertTrue(outcome.needsUpgrade)
        val upgraded = outcome.upgradedHash
        assertNotNull(upgraded)
        assertTrue(upgraded!!.formattedString.startsWith("pbkdf2\$600000\$"))
        // 升级后的 hash 必须能验证同一 PIN。
        assertTrue(
            ChatLockPinPolicy.verifyStoredPin("1234", upgraded.formattedString, upgraded.saltHex)
                is VerificationOutcome.Success,
        )
    }

    @Test
    fun `verifyStoredPin rejects malformed hashes without throwing`() {
        assertEquals(
            VerificationOutcome.Corrupted,
            ChatLockPinPolicy.verifyStoredPin("1234", "pbkdf2\$notanumber\$salt\$hash", "x"),
        )
        assertEquals(
            VerificationOutcome.Corrupted,
            ChatLockPinPolicy.verifyStoredPin("1234", "pbkdf2\$600000\$onlyonepart", "x"),
        )
    }

    @Test
    fun `verifyStoredPin returns Corrupted for a blank hash`() {
        assertEquals(
            VerificationOutcome.Corrupted,
            ChatLockPinPolicy.verifyStoredPin("1234", "", "x"),
        )
    }

    @Test
    fun `limiter locks after five failures and recovers when the clock advances`() {
        var now = 1_000L
        val limiter = ChatLockPinPolicy.newLimiter(clock = { now })
        repeat(4) { limiter.recordFailure("c1") }
        assertEquals(0L, limiter.remainingLockoutMs("c1"))
        assertFalse(limiter.isLocked("c1"))
        limiter.recordFailure("c1")
        assertTrue(limiter.isLocked("c1"))
        assertEquals(30_000L, limiter.remainingLockoutMs("c1"))
        now += 29_999L
        assertTrue(limiter.isLocked("c1"))
        assertEquals(1L, limiter.remainingLockoutMs("c1"))
        now += 1L
        assertEquals(0L, limiter.remainingLockoutMs("c1"))
        assertFalse(limiter.isLocked("c1"))
    }

    @Test
    fun `limiter success clears the failure count`() {
        val limiter = ChatLockPinPolicy.newLimiter(clock = { 1_000L })
        repeat(3) { limiter.recordFailure("c1") }
        limiter.recordSuccess("c1")
        repeat(4) { limiter.recordFailure("c1") }
        assertEquals(0L, limiter.remainingLockoutMs("c1"))
    }

    @Test
    fun `limiter reset clears a lockout`() {
        val limiter = ChatLockPinPolicy.newLimiter(clock = { 1_000L })
        repeat(5) { limiter.recordFailure("c1") }
        assertTrue(limiter.isLocked("c1"))
        limiter.reset("c1")
        assertEquals(0L, limiter.remainingLockoutMs("c1"))
    }
}
