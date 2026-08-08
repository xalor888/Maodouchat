package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.TokenManager

/** 1.175：输入框偏好（账号隔离存储）。 */
object ComposerPreferences {

    private const val PREFS_NAME = "composer_prefs"
    private const val KEY_ENTER_TO_SEND = "enter_to_send"

    private fun scopedKey(base: String, userId: String): String = "$base:$userId"

    /** 回车发送（默认关：回车换行）。 */
    fun enterToSend(context: Context): Boolean = getBoolean(context, KEY_ENTER_TO_SEND, false)

    fun setEnterToSend(context: Context, value: Boolean) {
        putBoolean(context, KEY_ENTER_TO_SEND, value)
    }

    private fun getBoolean(context: Context, key: String, default: Boolean): Boolean {
        val account = accountPreferences(context) ?: return default
        return account.prefs.getBoolean(scopedKey(key, account.userId), default)
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        val account = accountPreferences(context) ?: return
        account.prefs.edit().putBoolean(scopedKey(key, account.userId), value).apply()
    }

    private fun accountPreferences(context: Context): AccountPreferences? {
        val appContext = context.applicationContext
        val userId = TokenManager.getInstance(appContext).getUserId()?.takeIf(String::isNotBlank) ?: return null
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AccountPreferences(prefs, userId)
    }

    private data class AccountPreferences(val prefs: SharedPreferences, val userId: String)
}
