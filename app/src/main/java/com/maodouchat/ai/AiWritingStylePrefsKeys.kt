package com.maodouchat.ai

import android.content.Context
import com.maodouchat.network.TokenManager
import com.maodouchat.security.AccountIsolationPolicy

// 键与账号簇：prefs 文件名、键名、账号隔离键拼装、当前账号 userId。原 AiWritingStylePreferences 私有逻辑逐字搬入。
internal object AiWritingStylePrefsKeys {
    const val PREFS = "ai_writing_style"
    const val KEY_ENABLED = "enabled"
    const val KEY_PRESET = "preset"
    const val KEY_CUSTOM = "custom_note"

    fun prefs(context: Context): android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(base: String, userId: String): String =
        AccountIsolationPolicy.preferenceKey(base, userId)

    fun account(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf(String::isNotBlank)
}
