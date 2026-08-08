package com.maodouchat.ai

import com.maodouchat.network.TokenManager
import android.content.Context

/**
 * 自动图片 OCR（图内文字识别入搜索索引）的账号级本地开关。
 * 默认开启；仅存本机，不随设备同步。真正的隐私门槛仍以 [AiPrivacyPreferences.consentAccepted]
 * 为准：未同意 AI 处理时自动 OCR 不会运行（手动入口会先弹同意框）。
 */
object ImageOcrPreferences {
    private const val PREFS_NAME = "image_ocr_prefs"
    private const val KEY_ENABLED = "enabled"

    private fun scopedKey(base: String, userId: String): String = "$base:$userId"

    private fun userId(context: Context): String =
        TokenManager.getInstance(context).getUserId().orEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context)
        if (userId.isBlank()) return false
        return prefs(context).getBoolean(scopedKey(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context)
        if (userId.isBlank()) return
        prefs(context).edit().putBoolean(scopedKey(KEY_ENABLED, userId), enabled).apply()
    }
}
