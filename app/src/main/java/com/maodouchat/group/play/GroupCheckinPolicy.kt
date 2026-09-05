package com.maodouchat.group.play

/**
 * 群签到玩法策略（U06 解耦）。
 */
object GroupCheckinPolicy {
    const val CHECKIN_PREFIX = "CHECKIN:"

    fun formatCheckIn(dayStreak: Int, userLabel: String): String {
        return "${CHECKIN_PREFIX}$dayStreak|${userLabel} checked in · streak $dayStreak"
    }

    fun parseCheckIn(content: String): Pair<Int, String>? {
        if (!content.startsWith(CHECKIN_PREFIX)) return null
        val body = content.removePrefix(CHECKIN_PREFIX)
        val streak = body.substringBefore('|').toIntOrNull() ?: return null
        val label = body.substringAfter('|', "")
        return streak to label
    }
}
