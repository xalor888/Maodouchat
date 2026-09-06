package com.maodouchat.util

import android.content.Context

/** 1.175：输入框偏好（账号隔离存储）。 */
object ComposerPreferences {

    private const val PREFS_NAME = "composer_prefs"
    private const val KEY_ENTER_TO_SEND = "enter_to_send"

    /** 回车发送（默认关：回车换行）。 */
    fun enterToSend(context: Context): Boolean = getBoolean(context, KEY_ENTER_TO_SEND, false)

    fun setEnterToSend(context: Context, value: Boolean) {
        putBoolean(context, KEY_ENTER_TO_SEND, value)
    }

    private fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        AccountScopedPrefs.of(context, PREFS_NAME)?.getBoolean(key, default) ?: default

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        AccountScopedPrefs.of(context, PREFS_NAME)?.putBoolean(key, value)
    }
}
