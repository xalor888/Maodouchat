package com.maodouchat.security.pin

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinSecurityPolicyTest {

    @Test
    fun isValidPinFormat_validatesLengthAndDigits() {
        assertTrue(PinSecurityPolicy.isValidPinFormat("1234"))
        assertTrue(PinSecurityPolicy.isValidPinFormat("123456"))
        assertTrue(PinSecurityPolicy.isValidPinFormat("123456789012"))

        assertFalse(PinSecurityPolicy.isValidPinFormat("123")) // too short
        assertFalse(PinSecurityPolicy.isValidPinFormat("1234567890123")) // too long
        assertFalse(PinSecurityPolicy.isValidPinFormat("123a")) // non-digit
        assertFalse(PinSecurityPolicy.isValidPinFormat(""))
    }

    @Test
    fun hashPbkdf2_andVerify_successAndMismatch() {
        // Use lower iterations for fast unit test
        val testIter = 1000
        val salt = PinSecurityPolicy.generateSalt()
        val stored = PinSecurityPolicy.encodeStoredHash("5678", salt, testIter)

        assertTrue(stored.formattedString.startsWith("pbkdf2$" + testIter + "$"))

        val match = PinSecurityPolicy.verifyPin("5678", stored.formattedString, minDesiredIterations = testIter)
        assertTrue(match is PinSecurityPolicy.VerificationOutcome.Success)
        val success = match as PinSecurityPolicy.VerificationOutcome.Success
        assertFalse(success.needsUpgrade)
        assertNull(success.upgradedHash)

        val mismatch = PinSecurityPolicy.verifyPin("9999", stored.formattedString, minDesiredIterations = testIter)
        assertEquals(PinSecurityPolicy.VerificationOutcome.Mismatch, mismatch)
    }

    @Test
    fun constantTimeEquals_validatesEqualityAndRejectsInequality() {
        assertTrue(PinSecurityPolicy.constantTimeEquals("abcdef", "abcdef"))
        assertFalse(PinSecurityPolicy.constantTimeEquals("abcdef", "abcdeg"))
        assertFalse(PinSecurityPolicy.constantTimeEquals("abcdef", "abcde"))
        assertTrue(PinSecurityPolicy.constantTimeEquals("", ""))
    }

    @Test
    fun legacySha256_verifiesAndFlagsUpgrade() {
        val pin = "1234"
        val salt = "f0e1d2c3"
        val legacyHash = PinSecurityPolicy.hashSha256Legacy(pin, salt)

        // Verify with legacy SHA-256 hash (no pbkdf2 prefix)
        val outcome = PinSecurityPolicy.verifyPin(pin, legacyHash, salt, minDesiredIterations = 1000)
        assertTrue(outcome is PinSecurityPolicy.VerificationOutcome.Success)
        val success = outcome as PinSecurityPolicy.VerificationOutcome.Success
        assertTrue("Legacy SHA-256 must require upgrade", success.needsUpgrade)
        assertNotNull(success.upgradedHash)
        assertTrue(success.upgradedHash!!.isPbkdf2)
    }

    @Test
    fun rateLimiter_locksOutAfterMaxFailures_andResetsOnSuccess() {
        var currentTime = 1000000L
        val limiter = PinSecurityPolicy.RateLimiter(
            maxFailures = 3,
            lockoutMs = 30000L,
            clock = { currentTime }
        )

        assertFalse(limiter.isLocked("chat_1"))

        assertEquals(0L, limiter.recordFailure("chat_1"))
        assertFalse(limiter.isLocked("chat_1"))

        assertEquals(0L, limiter.recordFailure("chat_1"))
        assertFalse(limiter.isLocked("chat_1"))

        val lockout = limiter.recordFailure("chat_1")
        assertEquals(30000L, lockout)
        assertTrue(limiter.isLocked("chat_1"))
        assertEquals(30000L, limiter.remainingLockoutMs("chat_1"))

        // Advance time by 10s
        currentTime += 10000L
        assertTrue(limiter.isLocked("chat_1"))
        assertEquals(20000L, limiter.remainingLockoutMs("chat_1"))

        // Advance time past lockout
        currentTime += 20001L
        assertFalse(limiter.isLocked("chat_1"))
        assertEquals(0L, limiter.remainingLockoutMs("chat_1"))

        // Record success resets count
        limiter.recordFailure("chat_1")
        limiter.recordSuccess("chat_1")
        limiter.recordFailure("chat_1")
        assertFalse(limiter.isLocked("chat_1"))
    }

    @Test
    fun authenticatedSessionCache_enforcesTtlAndUserIsolation() {
        var currentTime = 500000L
        val cache = PinSecurityPolicy.AuthenticatedSessionCache(
            ttlMs = 60000L,
            clock = { currentTime }
        )

        cache.markAuthenticated("chat_42", "user_alice")
        assertTrue(cache.isAuthenticated("chat_42", "user_alice"))
        // User Bob should not be authenticated
        assertFalse(cache.isAuthenticated("chat_42", "user_bob"))

        // Advance time 50s - still valid
        currentTime += 50000L
        assertTrue(cache.isAuthenticated("chat_42", "user_alice"))

        // Advance time another 15s - expired
        currentTime += 15000L
        assertFalse(cache.isAuthenticated("chat_42", "user_alice"))
    }

    @Test
    fun defaultPinLockRepository_endToEndIntegration() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val repo = DefaultPinLockRepository(fakePrefs)

        assertFalse(repo.hasPin("wallet"))

        // Set pin
        assertTrue(repo.setPin("wallet", "8888"))
        assertTrue(repo.hasPin("wallet"))

        // Verify correct pin
        assertTrue(repo.verifyPin("wallet", "8888", "user_1"))
        assertTrue(repo.isSessionAuthenticated("wallet", "user_1"))
        assertFalse(repo.isSessionAuthenticated("wallet", "user_2"))

        // Verify wrong pin
        assertFalse(repo.verifyPin("wallet", "0000"))

        // Remove pin
        repo.removePin("wallet")
        assertFalse(repo.hasPin("wallet"))
        assertFalse(repo.isSessionAuthenticated("wallet", "user_1"))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val backingMap: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) backingMap.clear()
                pending.forEach { (k, v) ->
                    if (v == null) backingMap.remove(k) else backingMap[k] = v
                }
                pending.clear()
            }
        }
    }
}
