package com.maodouchat.util

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.domain.messaging.MediaAutoDownloadPolicy
import com.maodouchat.network.TokenManager

/**
 * 媒体自动下载偏好：按账号隔离；默认「仅 Wi-Fi」。
 * WIFI_ONLY / ALWAYS / OFF 三档，见 [MODE_WIFI_ONLY] 等常量。
 */
object MediaAutoDownloadPreferences {
    const val MODE_WIFI_ONLY = MediaAutoDownloadPolicy.MODE_WIFI_ONLY
    const val MODE_ALWAYS = MediaAutoDownloadPolicy.MODE_ALWAYS
    const val MODE_OFF = MediaAutoDownloadPolicy.MODE_OFF

    private const val PREFS_NAME = "chat_display_settings"
    private const val KEY_MODE = "media_auto_download_mode"

    fun getMode(context: Context): String {
        val userId = TokenManager.getInstance(context.applicationContext).getUserId()
            ?.takeIf { it.isNotBlank() } ?: return MODE_WIFI_ONLY
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(key(userId), null) ?: return MODE_WIFI_ONLY
        return normalize(saved)
    }

    fun setMode(context: Context, mode: String) {
        val userId = TokenManager.getInstance(context.applicationContext).getUserId()
            ?.takeIf { it.isNotBlank() } ?: return
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
            putString(key(userId), normalize(mode))
        }
    }

    fun normalizeForWrite(mode: String): String = MediaAutoDownloadPolicy.normalizeMode(mode)

    private fun normalize(mode: String): String = normalizeForWrite(mode)

    private fun key(userId: String): String = "${KEY_MODE}_$userId"
}
