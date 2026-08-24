package com.maodouchat.update

import android.content.Context

/** 同一 versionCode 只弹一次；用户点稍后后等下一版再弹。 */
object AppUpdatePromptStore {
    private const val PREFS = "app_update_prompt"
    private const val KEY_OFFERED = "offered_version_code"

    fun lastOfferedVersionCode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_OFFERED, 0)

    fun markOffered(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OFFERED, versionCode)
            .apply()
    }
}
