package com.maodouchat.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.maodouchat.network.TokenManager

/**
 * 敏感操作二次验证：复用 App 锁的系统生物识别 / 设备凭据。
 * 偏好按账号隔离；默认在 App 锁开启时要求二次验证。
 */
object SensitiveActionGate {
    private const val PREFS = "sensitive_action_gate"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context)
        if (userId.isBlank()) return false
        // 默认 true：开启 App 锁后敏感操作需 step-up，除非用户显式关闭
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context)
        if (userId.isBlank()) return
        prefs(context).edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply()
    }

    fun requiresStepUp(context: Context, action: SensitiveAction): Boolean =
        SensitiveActionPolicy.requiresStepUp(
            appLockEnabled = AppLockManager.isEnabled(context),
            sensitiveGateEnabled = isEnabled(context),
            action = action
        )

    /**
     * 若需要二次验证则弹出系统认证；不需要则直接 onSuccess。
     * activity 不可用或认证器不可用时：需要 step-up 则 onFailure，否则 onSuccess。
     */
    fun confirm(
        context: Context,
        action: SensitiveAction,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit = {}
    ) {
        val expectedUserId = userId(context)
        if (!requiresStepUp(context, action)) {
            if (expectedUserId.isNotBlank() && userId(context) == expectedUserId) {
                onSuccess()
            } else {
                onFailure(null)
            }
            return
        }
        val activity = context.findFragmentActivity()
        if (activity == null || !AppLockManager.isAuthenticationAvailable(context)) {
            onFailure(null)
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (expectedUserId.isNotBlank() && userId(context) == expectedUserId) {
                        onSuccess()
                    } else {
                        onFailure(null)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onFailure(null)
                    } else {
                        onFailure(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // 可继续重试；最终错误走 onAuthenticationError
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AppLockManager.authenticators())
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        prompt.authenticate(builder.build())
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(KEY_ENABLED, userId)).apply()
    }

    /**
     * 无条件弹出系统认证（与 [SensitiveActionPolicy] 门控解耦）。
     * 供 B2 密聊双因素门禁等独立二次验证场景使用：
     * activity 不可用或无可用认证器时直接 [onFailure]（调用方决定降级策略）。
     */
    fun confirmSystemAuth(
        context: Context,
        title: String,
        subtitle: String? = null,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit = {}
    ) {
        val expectedUserId = userId(context)
        val activity = context.findFragmentActivity()
        if (activity == null || !AppLockManager.isAuthenticationAvailable(context)) {
            onFailure(null)
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (expectedUserId.isNotBlank() && userId(context) == expectedUserId) {
                        onSuccess()
                    } else {
                        onFailure(null)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onFailure(null)
                    } else {
                        onFailure(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // 可继续重试；最终错误走 onAuthenticationError
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AppLockManager.authenticators())
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        prompt.authenticate(builder.build())
    }

    private fun userId(ctx: Context): String =
        TokenManager.getInstance(ctx).getUserId().orEmpty()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String): String = "$base:$userId"
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
