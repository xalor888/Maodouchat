package com.maodouchat.security.pin

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.security.pin.PinSecurityPolicy.RateLimiter
import com.maodouchat.security.pin.PinSecurityPolicy.AuthenticatedSessionCache
import com.maodouchat.security.pin.PinSecurityPolicy.VerificationOutcome

interface PinLockRepository {
    suspend fun hasPin(targetId: String): Boolean
    suspend fun setPin(targetId: String, pin: String): Boolean
    suspend fun verifyPin(targetId: String, pin: String, userId: String = ""): Boolean
    fun lockoutRemainingMs(targetId: String): Long
    suspend fun removePin(targetId: String)
    fun isSessionAuthenticated(targetId: String, userId: String): Boolean
    fun markSessionAuthenticated(targetId: String, userId: String)
    fun clearSession(targetId: String)
    fun clearAllSessions()
}

/**
 * 基于 SharedPreferences 与 PinSecurityPolicy 的安全 PIN 仓储实现
 */
class DefaultPinLockRepository(
    private val prefs: SharedPreferences,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val sessionCache: AuthenticatedSessionCache = AuthenticatedSessionCache()
) : PinLockRepository {

    constructor(context: Context, name: String = "pin_security_storage") : this(
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    )

    private companion object {
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
    }

    private fun key(base: String, targetId: String): String = "$base:$targetId"

    override suspend fun hasPin(targetId: String): Boolean {
        val hash = prefs.getString(key(KEY_PIN_HASH, targetId), null)
        return !hash.isNullOrBlank()
    }

    override suspend fun setPin(targetId: String, pin: String): Boolean {
        if (!PinSecurityPolicy.isValidPinFormat(pin)) return false
        val encoded = PinSecurityPolicy.encodeStoredHash(pin)
        prefs.edit()
            .putString(key(KEY_PIN_HASH, targetId), encoded.formattedString)
            .putString(key(KEY_PIN_SALT, targetId), encoded.saltHex)
            .apply()
        rateLimiter.reset(targetId)
        return true
    }

    override suspend fun verifyPin(targetId: String, pin: String, userId: String): Boolean {
        val storedHash = prefs.getString(key(KEY_PIN_HASH, targetId), null) ?: return true
        val storedSalt = prefs.getString(key(KEY_PIN_SALT, targetId), "").orEmpty()

        if (rateLimiter.isLocked(targetId)) return false

        val outcome = PinSecurityPolicy.verifyPin(pin, storedHash, storedSalt)
        return when (outcome) {
            is VerificationOutcome.Success -> {
                rateLimiter.recordSuccess(targetId)
                if (userId.isNotBlank()) {
                    sessionCache.markAuthenticated(targetId, userId)
                }
                if (outcome.needsUpgrade && outcome.upgradedHash != null) {
                    val upgraded = outcome.upgradedHash
                    prefs.edit()
                        .putString(key(KEY_PIN_HASH, targetId), upgraded.formattedString)
                        .putString(key(KEY_PIN_SALT, targetId), upgraded.saltHex)
                        .apply()
                }
                true
            }
            VerificationOutcome.Mismatch, VerificationOutcome.Corrupted -> {
                rateLimiter.recordFailure(targetId)
                false
            }
        }
    }

    override fun lockoutRemainingMs(targetId: String): Long =
        rateLimiter.remainingLockoutMs(targetId)

    override suspend fun removePin(targetId: String) {
        prefs.edit()
            .remove(key(KEY_PIN_HASH, targetId))
            .remove(key(KEY_PIN_SALT, targetId))
            .apply()
        rateLimiter.reset(targetId)
        sessionCache.invalidate(targetId)
    }

    override fun isSessionAuthenticated(targetId: String, userId: String): Boolean =
        sessionCache.isAuthenticated(targetId, userId)

    override fun markSessionAuthenticated(targetId: String, userId: String) =
        sessionCache.markAuthenticated(targetId, userId)

    override fun clearSession(targetId: String) =
        sessionCache.invalidate(targetId)

    override fun clearAllSessions() {
        sessionCache.invalidateAll()
    }
}
