package com.maodouchat.security.pin

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSecurityPolicyTest {

    @Test
    fun isValidPinFormat_validatesLengthAndDigits() {
        assertTrue(PinSecurityPolicy.isValidPinFormat("1234"))
        assertTrue(PinSecurityPolicy.isValidPinFormat("123456"))
        assertTrue(PinSecurityPolicy.isValidPinFormat("123456789012"))

        assertFalse(PinSecurityPolicy.isValidPinFormat("123"))
        assertFalse(PinSecurityPolicy.isValidPinFormat("1234567890123"))
        assertFalse(PinSecurityPolicy.isValidPinFormat("123a"))
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
        assertTrue(match is VerificationOutcome.Success)
        val success = match as VerificationOutcome.Success
        assertFalse(success.needsUpgrade)
        assertNull(success.upgradedHash)

        val mismatch = PinSecurityPolicy.verifyPin("9999", stored.formattedString, minDesiredIterations = testIter)
        assertEquals(VerificationOutcome.Mismatch, mismatch)
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
        assertTrue(outcome is VerificationOutcome.Success)
        val success = outcome as VerificationOutcome.Success
        assertTrue("Legacy SHA-256 must require upgrade", success.needsUpgrade)
        assertNotNull(success.upgradedHash)
        assertTrue(success.upgradedHash!!.isPbkdf2)
    }

    @Test
    fun rateLimiter_locksOutAfterMaxFailures_andResetsOnSuccess() {
        var currentTime = 1000000L
        val limiter = RateLimiter(
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

        currentTime += 10000L
        assertTrue(limiter.isLocked("chat_1"))
        assertEquals(20000L, limiter.remainingLockoutMs("chat_1"))

        currentTime += 20001L
        assertFalse(limiter.isLocked("chat_1"))
        assertEquals(0L, limiter.remainingLockoutMs("chat_1"))

        limiter.recordFailure("chat_1")
        limiter.recordSuccess("chat_1")
        limiter.recordFailure("chat_1")
        assertFalse(limiter.isLocked("chat_1"))
    }

    @Test
    fun authenticatedSessionCache_enforcesTtlAndUserIsolation() {
        var currentTime = 500000L
        val cache = AuthenticatedSessionCache(
            ttlMs = 60000L,
            clock = { currentTime }
        )

        cache.markAuthenticated("chat_42", "user_alice")
        assertTrue(cache.isAuthenticated("chat_42", "user_alice"))
        assertFalse(cache.isAuthenticated("chat_42", "user_bob"))

        currentTime += 50000L
        assertTrue(cache.isAuthenticated("chat_42", "user_alice"))

        currentTime += 15000L
        assertFalse(cache.isAuthenticated("chat_42", "user_alice"))
    }

    @Test
    fun defaultPinLockRepository_endToEndIntegration() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val repo = DefaultPinLockRepository(fakePrefs)

        assertFalse(repo.hasPin("wallet"))

        assertTrue(repo.setPin("wallet", "8888"))
        assertTrue(repo.hasPin("wallet"))

        assertTrue(repo.verifyPin("wallet", "8888", "user_1"))
        assertTrue(repo.isSessionAuthenticated("wallet", "user_1"))
        assertFalse(repo.isSessionAuthenticated("wallet", "user_2"))

        assertFalse(repo.verifyPin("wallet", "0000"))

        repo.removePin("wallet")
        assertFalse(repo.hasPin("wallet"))
        assertFalse(repo.isSessionAuthenticated("wallet", "user_1"))
    }

    @Test
    fun verifyPin_malformedStoredHash_returnsCorrupted() {
        assertEquals(VerificationOutcome.Corrupted, PinSecurityPolicy.verifyPin("1234", ""))
        assertEquals(VerificationOutcome.Corrupted, PinSecurityPolicy.verifyPin("1234", "pbkdf2$" + "1000"))
        assertEquals(VerificationOutcome.Corrupted, PinSecurityPolicy.verifyPin("1234", "pbkdf2$" + "abc" + "$" + "salt" + "$" + "hash"))
    }

    @Test
    fun verifyPin_lowIterationPbkdf2_flagsUpgradeWithReusableHash() {
        val salt = PinSecurityPolicy.generateSalt()
        val stored = PinSecurityPolicy.encodeStoredHash("5678", salt, 1000)
        assertEquals(32, stored.saltHex.length)

        val outcome = PinSecurityPolicy.verifyPin("5678", stored.formattedString, minDesiredIterations = 2000)
        assertTrue(outcome is VerificationOutcome.Success)
        val success = outcome as VerificationOutcome.Success
        assertTrue(success.needsUpgrade)
        assertNotNull(success.upgradedHash)
        assertTrue(success.upgradedHash!!.formattedString.startsWith("pbkdf2$" + 2000 + "$"))

        val reverify = PinSecurityPolicy.verifyPin(
            "5678",
            success.upgradedHash!!.formattedString,
            minDesiredIterations = 2000
        )
        assertTrue(reverify is VerificationOutcome.Success)
        assertFalse((reverify as VerificationOutcome.Success).needsUpgrade)
    }

    @Test
    fun rateLimiter_failuresAreIsolatedPerKey() {
        var currentTime = 2000000L
        val limiter = RateLimiter(maxFailures = 2, lockoutMs = 30000L, clock = { currentTime })

        assertEquals(0L, limiter.recordFailure("alice"))
        assertEquals(30000L, limiter.recordFailure("alice"))
        assertTrue(limiter.isLocked("alice"))

        assertFalse(limiter.isLocked("bob"))

        limiter.resetAll()
        assertFalse(limiter.isLocked("alice"))
    }

    @Test
    fun authenticatedSessionCache_invalidate_dropsPerUserAndAll() {
        var currentTime = 700000L
        val cache = AuthenticatedSessionCache(ttlMs = 60000L, clock = { currentTime })

        cache.markAuthenticated("chat_1", "user_alice")
        cache.markAuthenticated("chat_2", "user_alice")
        cache.markAuthenticated("chat_3", "user_bob")

        cache.invalidateUser("user_alice")
        assertFalse(cache.isAuthenticated("chat_1", "user_alice"))
        assertFalse(cache.isAuthenticated("chat_2", "user_alice"))
        assertTrue(cache.isAuthenticated("chat_3", "user_bob"))

        cache.invalidateAll()
        assertFalse(cache.isAuthenticated("chat_3", "user_bob"))
    }

    @Test
    fun pinLockRepository_setPinTwice_overwritesOldHash() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val repo = DefaultPinLockRepository(fakePrefs)

        assertFalse(repo.setPin("chat_x", "12"))
        assertFalse(repo.hasPin("chat_x"))

        assertTrue(repo.setPin("chat_x", "1111"))
        assertTrue(repo.verifyPin("chat_x", "1111"))

        assertTrue(repo.setPin("chat_x", "2222"))
        assertTrue(repo.verifyPin("chat_x", "2222"))
        assertFalse(repo.verifyPin("chat_x", "1111"))
    }


    @Test
    fun isValidPinFormat_allowsLeadingZerosAndRejectsSeparators() {
        assertTrue(PinSecurityPolicy.isValidPinFormat("0000"))
        assertTrue(PinSecurityPolicy.isValidPinFormat("0123"))
        assertFalse(PinSecurityPolicy.isValidPinFormat("12 4"))
        assertFalse(PinSecurityPolicy.isValidPinFormat("12-4"))
        // Char.isDigit 接受一切 Unicode 十进制数字，全角数字同样算数字
        assertTrue(PinSecurityPolicy.isValidPinFormat("１２３４"))
    }

    @Test
    fun hashPbkdf2_isDeterministicAndSensitiveToSaltAndIterations() {
        val salt = "00112233445566778899aabbccddeeff"
        val first = PinSecurityPolicy.hashPbkdf2("5678", salt, 1000)
        assertEquals(64, first.length)
        assertEquals(first, PinSecurityPolicy.hashPbkdf2("5678", salt, 1000))
        assertTrue(first != PinSecurityPolicy.hashPbkdf2("5678", "ffeeddccbbaa99887766554433221100", 1000))
        assertTrue(first != PinSecurityPolicy.hashPbkdf2("5678", salt, 1001))
    }

    @Test
    fun generateSalt_hasRequestedLengthAndHexAlphabet() {
        val salt = PinSecurityPolicy.generateSalt()
        assertEquals(32, salt.length)
        assertTrue(salt.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(16, PinSecurityPolicy.generateSalt(8).length)
        assertTrue(salt != PinSecurityPolicy.generateSalt())
    }

    @Test
    fun verifyPin_malformedVariants_returnsCorrupted() {
        assertEquals(VerificationOutcome.Corrupted, PinSecurityPolicy.verifyPin("1234", "   "))
        assertEquals(
            VerificationOutcome.Corrupted,
            PinSecurityPolicy.verifyPin("1234", "pbkdf2$" + "1000" + "$" + "salt")
        )
        assertEquals(
            VerificationOutcome.Corrupted,
            PinSecurityPolicy.verifyPin(
                "1234",
                "pbkdf2$" + "1000" + "$" + "salt" + "$" + "hash" + "$" + "extra"
            )
        )
    }

    @Test
    fun verifyPin_legacyMismatch_returnsMismatch() {
        val legacyHash = PinSecurityPolicy.hashSha256Legacy("1234", "f0e1d2c3")
        assertEquals(VerificationOutcome.Mismatch, PinSecurityPolicy.verifyPin("9999", legacyHash, "f0e1d2c3"))
        assertEquals(VerificationOutcome.Mismatch, PinSecurityPolicy.verifyPin("1234", legacyHash, "other-salt"))
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
