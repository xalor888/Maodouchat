package com.maodouchat.util

import android.content.Context

/**
 * 未读智能优先开关（账号隔离，默认开）。
 */
object UnreadPriorityPreferences {
    private val switch = AccountFeatureSwitch("unread_priority")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)
}
