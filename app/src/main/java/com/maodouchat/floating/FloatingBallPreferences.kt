package com.maodouchat.floating

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * B5 悬浮球偏好（按账号隔离，与 AccountIsolationPolicy 键约定一致）。
 * 位置按屏幕宽高百分比存储，适应不同屏幕尺寸。
 */
object FloatingBallPreferences {

    private const val PREFS = "floating_ball"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_POS_X = "pos_x"
    private const val KEY_POS_Y = "pos_y"
    private const val KEY_REQUESTED = "permission_requested"

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context)
        if (userId.isBlank()) return false
        return prefs(context).getBoolean(accountKey(KEY_ENABLED, userId), false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context)
        if (userId.isBlank()) return
        prefs(context).edit().putBoolean(accountKey(KEY_ENABLED, userId), enabled).apply()
    }

    /** 默认位置：右上角 */
    fun position(context: Context): Pair<Float, Float> {
        val userId = userId(context)
        if (userId.isBlank()) return DEFAULT_X to DEFAULT_Y
        val prefs = prefs(context)
        val x = prefs.getFloat(accountKey(KEY_POS_X, userId), DEFAULT_X)
        val y = prefs.getFloat(accountKey(KEY_POS_Y, userId), DEFAULT_Y)
        return x to y
    }

    fun setPosition(context: Context, xRatio: Float, yRatio: Float) {
        val userId = userId(context)
        if (userId.isBlank()) return
        prefs(context).edit()
            .putFloat(accountKey(KEY_POS_X, userId), xRatio)
            .putFloat(accountKey(KEY_POS_Y, userId), yRatio)
            .apply()
    }

    fun wasRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    private fun userId(ctx: Context): String =
        TokenManager.getInstance(ctx).getUserId().orEmpty()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun accountKey(base: String, userId: String): String = "$base:$userId"

    private const val DEFAULT_X = 0.88f
    private const val DEFAULT_Y = 0.45f
}
