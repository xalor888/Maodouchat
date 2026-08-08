package com.maodouchat.security

import com.maodouchat.util.RuntimeFlags
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import com.maodouchat.network.TokenManager

object AppLockPolicy {
    fun shouldLock(
        enabled: Boolean,
        loggedIn: Boolean,
        authenticatedForCurrentUser: Boolean,
        backgroundAtMillis: Long,
        timeoutMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (!enabled || !loggedIn) return false
        if (!authenticatedForCurrentUser) return true
        if (backgroundAtMillis <= 0L) return false
        val elapsed = nowMillis - backgroundAtMillis
        return elapsed < 0L || elapsed >= timeoutMillis.coerceAtLeast(1L)
    }
}

/** Account-scoped application lock backed by system biometric/device credentials. */
object AppLockManager {
    private const val PREFS = "app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMEOUT_MINUTES = "timeout_minutes"
    private const val KEY_BACKGROUND_AT = "background_at_millis"
    private const val DEFAULT_TIMEOUT_MINUTES = 5L
    private val ALLOWED_TIMEOUTS = setOf(1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L)

    fun authenticators(): Int =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            // Android 9/10 cannot combine BIOMETRIC_STRONG with DEVICE_CREDENTIAL via AndroidX.
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }) or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    @Volatile
    private var authenticatedUserId: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun userId(ctx: Context): String = TokenManager.getInstance(ctx).getUserId().orEmpty()
    private fun key(base: String, userId: String): String = "$base:$userId"

    private fun migrateLegacySettings(ctx: Context, userId: String) {
        val prefs = prefs(ctx)
        val accountEnabledKey = key(KEY_ENABLED, userId)
        if (prefs.contains(accountEnabledKey) || !prefs.contains(KEY_ENABLED)) return
        // 迁移只需写入一次，apply() 异步提交足够；commit() 在主线程调用会阻塞 UI
        prefs.edit()
            .putBoolean(accountEnabledKey, prefs.getBoolean(KEY_ENABLED, false))
            .putLong(
                key(KEY_TIMEOUT_MINUTES, userId),
                prefs.getLong(KEY_TIMEOUT_MINUTES, DEFAULT_TIMEOUT_MINUTES)
            )
            .putLong(key(KEY_BACKGROUND_AT, userId), prefs.getLong(KEY_BACKGROUND_AT, 0L))
            .remove(KEY_ENABLED)
            .remove(KEY_TIMEOUT_MINUTES)
            .remove(KEY_BACKGROUND_AT)
            .apply()
    }

    fun isAuthenticationAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(ctx: Context): Boolean {
        if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.APP_LOCK)) return false
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        migrateLegacySettings(ctx, userId)
        return prefs(ctx).getBoolean(key(KEY_ENABLED, userId), false)
    }

    /** Returns false when enabling would lock the user out because no system authenticator exists. */
    fun setEnabled(ctx: Context, enabled: Boolean): Boolean {
        if (enabled && !RuntimeFlags.isEnabled(ctx, RuntimeFlags.APP_LOCK)) return false
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        if (enabled && !isAuthenticationAvailable(ctx)) return false
        prefs(ctx).edit()
            .putBoolean(key(KEY_ENABLED, userId), enabled)
            .remove(key(KEY_BACKGROUND_AT, userId))
            .apply()
        authenticatedUserId = userId.takeIf { enabled }
        return true
    }

    fun getTimeoutMinutes(ctx: Context): Long {
        val userId = userId(ctx)
        if (userId.isBlank()) return DEFAULT_TIMEOUT_MINUTES
        migrateLegacySettings(ctx, userId)
        return prefs(ctx).getLong(key(KEY_TIMEOUT_MINUTES, userId), DEFAULT_TIMEOUT_MINUTES)
            .takeIf(ALLOWED_TIMEOUTS::contains)
            ?: DEFAULT_TIMEOUT_MINUTES
    }

    fun setTimeoutMinutes(ctx: Context, minutes: Long) {
        val userId = userId(ctx)
        if (userId.isBlank()) return
        val safeMinutes = minutes.takeIf(ALLOWED_TIMEOUTS::contains) ?: DEFAULT_TIMEOUT_MINUTES
        prefs(ctx).edit().putLong(key(KEY_TIMEOUT_MINUTES, userId), safeMinutes).apply()
    }

    fun noteBackground(ctx: Context, nowMillis: Long = System.currentTimeMillis()) {
        val userId = userId(ctx)
        if (userId.isBlank() || !isEnabled(ctx)) return
        prefs(ctx).edit().putLong(key(KEY_BACKGROUND_AT, userId), nowMillis).apply()
    }

    fun shouldLock(ctx: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        val backgroundAt = prefs(ctx).getLong(key(KEY_BACKGROUND_AT, userId), 0L)
        return AppLockPolicy.shouldLock(
            enabled = isEnabled(ctx),
            loggedIn = TokenManager.getInstance(ctx).isLoggedIn(),
            authenticatedForCurrentUser = authenticatedUserId == userId,
            backgroundAtMillis = backgroundAt,
            timeoutMillis = getTimeoutMinutes(ctx) * 60_000L,
            nowMillis = nowMillis
        )
    }

    fun markUnlocked(ctx: Context) {
        val userId = userId(ctx)
        if (userId.isBlank()) return
        authenticatedUserId = userId
        prefs(ctx).edit().remove(key(KEY_BACKGROUND_AT, userId)).apply()
    }

    /** Test/process reset hook used when an account is removed from this process. */
    fun clearAuthenticatedSession() {
        authenticatedUserId = null
    }
}
