package com.maodouchat.security

import com.maodouchat.MainActivity
import com.maodouchat.network.TokenManager
import com.maodouchat.util.RuntimeFlags
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64

/**
 * 假聊天模式 / 隐藏桌面图标（隐私伪装）。
 *
 * 职责：
 * - 假聊天模式（armed）：开启后 App 每次回到前台先展示「假聊天」界面，真实内容完全不可见，
 *   直到在假界面里通过隐藏手势 + 专属密码解锁。解锁状态默认在退到后台后失效（可配置）。
 * - 隐藏桌面图标：禁用 Launcher 入口 Activity，桌面图标消失；通过拨号盘输入 `*#*#75263#*#*`
 *   恢复图标并拉起 App（见 [SecretCodeReceiver]）。
 *
 * 所有设置均按账号隔离（同 AppLockManager），仅存本机 SharedPreferences，不上传服务器。
 * 密码以 PBKDF2 哈希存储（salt + iterations + hash），不再保存明文；
 * 连续失败 [MAX_FAILURES] 次后进入 [LOCKOUT_MS] 退避，防暴力尝试。
 */
object FakeChatPolicy {
    fun shouldShowFake(
        enabled: Boolean,
        hasPin: Boolean,
        unlockedForCurrentUser: Boolean,
        relockOnBackground: Boolean,
        backgroundAtMillis: Long
    ): Boolean {
        if (!enabled || !hasPin) return false
        if (unlockedForCurrentUser) {
            if (!relockOnBackground) return false
            if (backgroundAtMillis <= 0L) return false
        }
        return true
    }
}

object FakeChatManager {
    private const val PREFS = "fake_chat"

    /** 拨号盘恢复码：`*#*#75263#*#*`（manifest 中 receiver 的 host 与此一致，勿改） */
    const val SECRET_CODE = "75263"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_PIN = "pin"
    private const val KEY_HIDE_ICON = "hide_icon"
    private const val KEY_RELOCK_BACKGROUND = "relock_on_background"
    private const val KEY_BACKGROUND_AT = "background_at_millis"
    private const val KEY_FAILURES = "pin_failures"
    private const val KEY_LOCKED_UNTIL = "pin_locked_until"

    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 12
    private const val PBKDF2_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MS = 30_000L

    /** 当前进程内的解锁标记（进程存活期间有效，退后台后按需清除） */
    @Volatile
    private var unlockedUserId: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun userId(ctx: Context): String = TokenManager.getInstance(ctx).getUserId().orEmpty()
    private fun key(base: String, userId: String): String = "$base:$userId"

    fun isPinValid(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)

    // ---- 假聊天模式开关 ----

    fun isEnabled(ctx: Context): Boolean {
        if (!RuntimeFlags.isEnabled(ctx, RuntimeFlags.FAKE_CHAT)) return false
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        return prefs(ctx).getBoolean(key(KEY_ENABLED, userId), false)
    }

    /** 开启必须已设置 PIN，否则返回 false 且不写入，避免无解锁口的死锁入口。 */
    fun setEnabled(ctx: Context, enabled: Boolean): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        // Hide-app / fake-chat is a trap: leftover prefs can still intercept the real UI.
        if (enabled) {
            Log.w("FakeChatManager", "refusing to enable fake chat")
            return false
        }
        prefs(ctx).edit()
            .putBoolean(key(KEY_ENABLED, userId), enabled)
            .remove(key(KEY_BACKGROUND_AT, userId))
            .apply()
        if (!enabled) {
            if (unlockedUserId == userId) unlockedUserId = null
        }
        return true
    }

    // ---- 解锁密码（PBKDF2 哈希） ----

    fun hasPin(ctx: Context): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        return prefs(ctx).contains(key(KEY_PIN, userId))
    }

    /** 设置/更新解锁密码。拒绝默认弱密码与非法格式；仅存 salt+hash。成功返回 true。 */
    fun setPin(ctx: Context, pin: String): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank() || !isPinValid(pin)) return false
        if (pin == "0000" || pin == "1234") return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt, PBKDF2_ITERATIONS)
        prefs(ctx).edit()
            .putString(
                key(KEY_PIN, userId),
                "$PBKDF2_ITERATIONS:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(hash)}"
            )
            .remove(key(KEY_FAILURES, userId))
            .remove(key(KEY_LOCKED_UNTIL, userId))
            .apply()
        return true
    }

    /** 校验解锁密码；失败退避 + 失败计数。旧明文存储自动迁移为哈希。 */
    fun checkPin(ctx: Context, pin: String): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        val stored = prefs(ctx).getString(key(KEY_PIN, userId), null)
        // 未设置 PIN：绝不接受任何输入（不再有默认密码兜底）
        if (stored == null) return false
        val now = System.currentTimeMillis()
        val lockedUntil = prefs(ctx).getLong(key(KEY_LOCKED_UNTIL, userId), 0L)
        if (lockedUntil > now) return false
        val ok = if (stored.contains(':')) {
            verifyPbkdf2(pin, stored)
        } else {
            // 旧版本明文存储迁移：匹配则升级为哈希，不匹配按失败处理
            val legacyOk = stored == pin
            if (legacyOk) {
                prefs(ctx).edit()
                    .remove(key(KEY_PIN, userId))
                    .apply()
                setPin(ctx, pin)
            }
            legacyOk
        }
        if (!ok) {
            val failures = prefs(ctx).getInt(key(KEY_FAILURES, userId), 0) + 1
            val edit = prefs(ctx).edit().putInt(key(KEY_FAILURES, userId), failures)
            if (failures >= MAX_FAILURES) {
                edit.putLong(key(KEY_LOCKED_UNTIL, userId), now + LOCKOUT_MS)
                edit.remove(key(KEY_FAILURES, userId))
            }
            edit.apply()
            return false
        }
        prefs(ctx).edit().remove(key(KEY_FAILURES, userId)).remove(key(KEY_LOCKED_UNTIL, userId)).apply()
        return true
    }

    /** 连续失败退避剩余毫秒；0 表示未锁定。 */
    fun lockoutRemainingMs(ctx: Context): Long {
        val userId = userId(ctx)
        if (userId.isBlank()) return 0L
        return (prefs(ctx).getLong(key(KEY_LOCKED_UNTIL, userId), 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun verifyPbkdf2(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val actual = pbkdf2(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    // ---- 前台拦截（armed / unlock / relock） ----

    fun isRelockOnBackground(ctx: Context): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return true
        return prefs(ctx).getBoolean(key(KEY_RELOCK_BACKGROUND, userId), true)
    }

    fun setRelockOnBackground(ctx: Context, value: Boolean) {
        val userId = userId(ctx)
        if (userId.isBlank()) return
        prefs(ctx).edit().putBoolean(key(KEY_RELOCK_BACKGROUND, userId), value).apply()
    }

    /** 是否应该用假聊天界面拦截前台（冷启动 / 从后台返回）。无 PIN 时绝不拦截，避免死锁。 */
    fun shouldShowFake(ctx: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        val backgroundAt = prefs(ctx).getLong(key(KEY_BACKGROUND_AT, userId), 0L)
        return FakeChatPolicy.shouldShowFake(
            enabled = isEnabled(ctx),
            hasPin = hasPin(ctx),
            unlockedForCurrentUser = unlockedUserId == userId,
            relockOnBackground = isRelockOnBackground(ctx),
            backgroundAtMillis = backgroundAt
        )
    }

    /** 假界面内通过密码验证成功后调用，进入真实 App。 */
    fun markUnlocked(ctx: Context) {
        val userId = userId(ctx)
        if (userId.isBlank()) return
        unlockedUserId = userId
        prefs(ctx).edit().remove(key(KEY_BACKGROUND_AT, userId)).apply()
    }

    /** 退到后台时记录时间戳；若开启了「回前台重锁」则由 shouldShowFake 依据它重新拦截。 */
    fun noteBackground(ctx: Context, nowMillis: Long = System.currentTimeMillis()) {
        val userId = userId(ctx)
        if (userId.isBlank() || !isEnabled(ctx)) return
        prefs(ctx).edit().putLong(key(KEY_BACKGROUND_AT, userId), nowMillis).apply()
    }

    /** 手动锁定（设置页关闭功能时也会隐式调用）。 */
    fun lockNow(ctx: Context) {
        val userId = userId(ctx)
        if (userId.isBlank()) return
        if (unlockedUserId == userId) unlockedUserId = null
        prefs(ctx).edit().putLong(key(KEY_BACKGROUND_AT, userId), System.currentTimeMillis()).apply()
    }

    /** 账号被移除 / 进程会话重置时调用（对齐 AppLockManager.clearAuthenticatedSession）。 */
    fun clearUnlockedSession() {
        unlockedUserId = null
    }

    // ---- 隐藏桌面图标 ----

    fun isLauncherIconHidden(ctx: Context): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        return prefs(ctx).getBoolean(key(KEY_HIDE_ICON, userId), false)
    }

    /** 拨号码恢复桌面图标后同步偏好，避免设置页继续显示“已隐藏”。 */
    fun markLauncherIconRestored(ctx: Context) {
        val userId = userId(ctx)
        if (userId.isBlank()) return
        prefs(ctx).edit().remove(key(KEY_HIDE_ICON, userId)).apply()
    }

    /**
     * 登出/换号：若该账号曾隐藏桌面图标，恢复全局 Launcher 组件，避免下一账号找不到入口。
     * 组件状态是进程级/包级的，不是按账号隔离的。
     */
    fun restoreLauncherIfHiddenForUser(ctx: Context, userId: String) {
        if (userId.isBlank()) return
        val hidden = prefs(ctx).getBoolean(key(KEY_HIDE_ICON, userId), false)
        if (!hidden) return
        val restored = runCatching {
            val pm = ctx.packageManager
            val component = ComponentName(ctx, MainActivity::class.java)
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            true
        }.getOrElse {
            Log.w("FakeChatManager", "restore launcher icon failed", it)
            false
        }
        if (restored) {
            prefs(ctx).edit().remove(key(KEY_HIDE_ICON, userId)).apply()
        }
    }

    /**
     * 隐藏/恢复桌面图标。隐藏后只能通过拨号盘 `*#*#75263#*#*` 恢复并拉起 App。
     * 返回 false 表示操作失败（例如系统组件状态无法写入）。
     */
    fun setLauncherIconHidden(ctx: Context, hidden: Boolean): Boolean {
        val userId = userId(ctx)
        if (userId.isBlank()) return false
        // Hiding the launcher icon is a trap: many launchers never restore from the
        // dialer secret code, so the only recovery is uninstall. Disable hide.
        if (hidden) {
            Log.w("FakeChatManager", "refusing to hide launcher icon")
            return false
        }
        val pm = ctx.packageManager
        val component = ComponentName(ctx, MainActivity::class.java)
        val newState = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        return runCatching {
            pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP)
            true
        }.getOrElse {
            android.util.Log.w("FakeChatManager", "setComponentEnabledSetting failed", it)
            false
        }.also { success ->
            if (success) {
                prefs(ctx).edit().putBoolean(key(KEY_HIDE_ICON, userId), hidden).apply()
            }
        }
    }
}
