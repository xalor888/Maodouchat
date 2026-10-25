package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNotificationSuppressPolicyTest {

    @Test
    fun globalOffSuppresses() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = false,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 12
            )
        )
    }

    @Test
    fun equalBoundsMeansDndOff() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 22,
                hourOfDay = 22
            )
        )
    }

    @Test
    fun daytimeWindow() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 12
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 17
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 8
            )
        )
    }

    @Test
    fun overnightWindow() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23
            )
        )
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 3
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 12
            )
        )
    }

    @Test
    fun explicitDndOffNeverSuppressesWhenNotificationsOn() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23,
                dndEnabled = false,
                startMinute = 22 * 60,
                endMinute = 7 * 60,
                currentMinute = 23 * 60
            )
        )
    }

    @Test
    fun minutePrecisionInsideOvernightWindow() {
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 22,
                dndEnabled = true,
                startMinute = 22 * 60 + 30,
                endMinute = 7 * 60,
                currentMinute = 22 * 60 + 45
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 22,
                dndEnabled = true,
                startMinute = 22 * 60 + 30,
                endMinute = 7 * 60,
                currentMinute = 22 * 60 + 10
            )
        )
    }

    @Test
    fun runtimeFlagOffBypassesDndWindow() {
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23,
                dndRuntimeEnabled = false,
                dndEnabled = true
            )
        )
    }

    @Test
    fun minuteWindowSameDayEndExclusive() {
        // 分钟精度同日窗口同样是 [start, end) 半开区间。
        fun suppressAt(minute: Int) = LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = true,
            dndStartHour = 9,
            dndEndHour = 17,
            hourOfDay = 12,
            dndEnabled = true,
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            currentMinute = minute
        )
        assertTrue(suppressAt(9 * 60))
        assertTrue(suppressAt(17 * 60 - 1))
        assertFalse(suppressAt(17 * 60))
    }

    @Test
    fun hourFallbackKeepsLegacySemantics() {
        // currentMinute=null 但给了分钟窗口：按 hourOfDay*60 回退（兼容旧调用）。
        fun suppressAt(hour: Int) = LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = true,
            dndStartHour = 9,
            dndEndHour = 17,
            hourOfDay = hour,
            dndEnabled = true,
            startMinute = 9 * 60,
            endMinute = 17 * 60
        )
        assertTrue(suppressAt(12))
        assertFalse(suppressAt(17))
    }

    @Test
    fun outOfRangeInputsAreCoerced() {
        // 输入先钳制再比较：负分钟→0，超限小时→23。
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 12,
                dndEnabled = true,
                startMinute = 22 * 60,
                endMinute = 7 * 60,
                currentMinute = -5
            )
        )
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 9,
                dndEndHour = 17,
                hourOfDay = 30,
                dndEnabled = true,
                startMinute = 9 * 60,
                endMinute = 17 * 60
            )
        )
    }

    @Test
    fun dndEnabledNullMeansLegacyEnabled() {
        // dndEnabled 不传时默认启用（兼容旧版"未设置计划即关闭"之外的旧调用）。
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 23,
                startMinute = 22 * 60 + 30,
                endMinute = 7 * 60,
                currentMinute = 23 * 60 + 20
            )
        )
    }

    @Test
    fun globalOffDominatesAllFlags() {
        // 全局关闭压过一切：即使运行时开关与显式开关都关，仍抑制。
        assertTrue(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = false,
                dndStartHour = 22,
                dndEndHour = 7,
                hourOfDay = 12,
                dndRuntimeEnabled = false,
                dndEnabled = false,
                startMinute = 0,
                endMinute = 1439,
                currentMinute = 12 * 60
            )
        )
    }

    @Test
    fun overnightWindowStartInclusiveEndExclusive() {
        // 跨天窗口同样半开：起点含、终点不含，午夜在窗口内。
        fun suppressAt(minute: Int) = LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = true,
            dndStartHour = 22,
            dndEndHour = 7,
            hourOfDay = 12,
            dndEnabled = true,
            startMinute = 22 * 60 + 30,
            endMinute = 7 * 60,
            currentMinute = minute
        )
        assertTrue(suppressAt(22 * 60 + 30))
        assertFalse(suppressAt(7 * 60))
        assertTrue(suppressAt(0))
    }

    @Test
    fun currentMinuteUpperBoundCoercedToEndOfDay() {
        fun suppressAt(minute: Int, startHour: Int, endHour: Int) =
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = startHour,
                dndEndHour = endHour,
                hourOfDay = 12,
                dndEnabled = true,
                startMinute = startHour * 60,
                endMinute = endHour * 60,
                currentMinute = minute
            )
        assertTrue(suppressAt(2000, 22, 7))
        assertFalse(suppressAt(2000, 9, 17))
    }

    @Test
    fun negativeHourCoercedToMidnight() {
        fun suppressAt(hour: Int, startHour: Int, endHour: Int) =
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = startHour,
                dndEndHour = endHour,
                hourOfDay = hour,
                dndEnabled = true
            )
        assertTrue(suppressAt(-1, 22, 7))
        assertFalse(suppressAt(-1, 9, 17))
    }

    @Test
    fun mixedMinuteStartWithHourEndFallback() {
        // 只给分钟起点时终点仍按小时回退。
        fun suppressAt(minute: Int) = LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = true,
            dndStartHour = 9,
            dndEndHour = 17,
            hourOfDay = 12,
            dndEnabled = true,
            startMinute = 9 * 60,
            currentMinute = minute
        )
        assertTrue(suppressAt(10 * 60))
        assertFalse(suppressAt(17 * 60))
    }

    @Test
    fun mixedHourStartWithMinuteEndFallback() {
        // 只给分钟终点时起点仍按小时回退，跨天判定不变。
        fun suppressAt(minute: Int) = LocalNotificationSuppressPolicy.shouldSuppress(
            notificationsEnabled = true,
            dndStartHour = 22,
            dndEndHour = 7,
            hourOfDay = 12,
            dndEnabled = true,
            endMinute = 7 * 60,
            currentMinute = minute
        )
        assertTrue(suppressAt(23 * 60))
        assertFalse(suppressAt(8 * 60 + 20))
    }

    @Test
    fun equalMinuteBoundsMeansDndOff() {
        // 分钟参数同样：start==end 视为关闭。
        assertFalse(
            LocalNotificationSuppressPolicy.shouldSuppress(
                notificationsEnabled = true,
                dndStartHour = 10,
                dndEndHour = 10,
                hourOfDay = 10,
                dndEnabled = true,
                startMinute = 600,
                endMinute = 600,
                currentMinute = 600
            )
        )
    }
}
