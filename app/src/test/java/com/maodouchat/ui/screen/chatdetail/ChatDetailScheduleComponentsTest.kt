package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.content.res.Resources
import com.maodouchat.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G149：`ChatDetailScheduleComponents.kt` 里两个时间显示函数的测试。
 *
 * 两者都只做「选单位 + 取整 + 拼字符串」，逻辑全在分档与取整上——
 * 用 mockk 把 `Context.getString` / `resources.getQuantityString` 打成
 * 「回显资源名与参数」的桩，就能只验分档与取整，不依赖真实资源。
 */
class ChatDetailScheduleComponentsTest {

    /** 桩 Context：getString(id, ...args) -> "name(args)"，便于断言选中的是哪一档。 */
    private fun stubContext(): Context {
        val res = mockk<Resources>(relaxed = true)
        every { res.getQuantityString(R.plurals.schedule_repeat_remaining, any(), any()) } answers
            { "remaining(${args[1]})" }
        return mockk<Context>(relaxed = true).also { ctx ->
            every { ctx.resources } returns res
            every { ctx.getString(R.string.time_just_now) } returns "just_now"
            every { ctx.getString(R.string.chat_mute_minutes, any<Long>()) } answers
                { "min(${(args[1] as Array<*>).first()})" }
            every { ctx.getString(R.string.chat_mute_hours, any<Long>()) } answers
                { "hr(${(args[1] as Array<*>).first()})" }
            every { ctx.getString(R.string.chat_mute_days, any<Long>()) } answers
                { "day(${(args[1] as Array<*>).first()})" }
            every { ctx.getString(R.string.schedule_repeat_weekdays) } returns "weekdays"
            every { ctx.getString(R.string.schedule_repeat_daily) } returns "daily"
            every { ctx.getString(R.string.schedule_repeat_weekly) } returns "weekly"
            every { ctx.getString(R.string.schedule_repeat_badge) } returns "badge"
        }
    }

    private val ctx = stubContext()

    // ─── formatMuteRemaining ───

    @Test
    fun `mute remaining picks the minute unit below one hour`() {
        assertEquals("min(1)", formatMuteRemaining(ctx, 60_000L))
        assertEquals("min(30)", formatMuteRemaining(ctx, 30 * 60_000L))
        assertEquals("min(59)", formatMuteRemaining(ctx, 59 * 60_000L))
    }

    @Test
    fun `mute remaining falls back to just now at or below zero`() {
        assertEquals("just_now", formatMuteRemaining(ctx, 0L))
        // 59 秒整数除法得 0 分钟 —— 必须走 just_now，不能显示 "0 分钟"
        assertEquals("just_now", formatMuteRemaining(ctx, 59_000L))
        // 60 秒恰好 1 分钟，进分钟档
        assertEquals("min(1)", formatMuteRemaining(ctx, 60_000L))
        assertEquals("just_now", formatMuteRemaining(ctx, -5 * 60_000L))
    }

    @Test
    fun `mute remaining picks the hour unit between one hour and one day`() {
        assertEquals("hr(1)", formatMuteRemaining(ctx, 60 * 60_000L))
        // 90 分钟整数除法得 1 小时
        assertEquals("hr(1)", formatMuteRemaining(ctx, 90 * 60_000L))
        assertEquals("hr(23)", formatMuteRemaining(ctx, 23 * 60 * 60_000L))
        // 23:59 仍是小时档
        assertEquals("hr(23)", formatMuteRemaining(ctx, 23 * 60 * 60_000L + 59 * 60_000L))
    }

    @Test
    fun `mute remaining picks the day unit from one day up`() {
        assertEquals("day(1)", formatMuteRemaining(ctx, 24 * 60 * 60_000L))
        // 25 小时整数除法得 1 天
        assertEquals("day(1)", formatMuteRemaining(ctx, 25 * 60 * 60_000L))
        assertEquals("day(7)", formatMuteRemaining(ctx, 7 * 24 * 60 * 60_000L))
    }

    // ─── scheduleRepeatLabel ───

    @Test
    fun `weekdays only wins over the interval`() {
        assertEquals("weekdays", scheduleRepeatLabel(ctx, 24L * 3600_000L, 0, 0, weekdaysOnly = true))
    }

    @Test
    fun `exact daily and weekly intervals get their own label`() {
        assertEquals("daily", scheduleRepeatLabel(ctx, 24L * 3600_000L, 0, 0, weekdaysOnly = false))
        assertEquals("weekly", scheduleRepeatLabel(ctx, 7L * 24 * 3600_000L, 0, 0, weekdaysOnly = false))
    }

    @Test
    fun `arbitrary positive interval falls back to the generic badge`() {
        assertEquals("badge", scheduleRepeatLabel(ctx, 3L * 3600_000L, 0, 0, weekdaysOnly = false))
        // 差一小时不算每日
        assertEquals("badge", scheduleRepeatLabel(ctx, 23L * 3600_000L, 0, 0, weekdaysOnly = false))
    }

    @Test
    fun `non positive interval returns null`() {
        assertNull(scheduleRepeatLabel(ctx, 0L, 0, 0, weekdaysOnly = false))
        assertNull(scheduleRepeatLabel(ctx, -1L, 0, 0, weekdaysOnly = false))
    }

    @Test
    fun `repeat count appends the remaining occurrences`() {
        assertEquals("daily · remaining(3)", scheduleRepeatLabel(ctx, 24L * 3600_000L, 5, 2, weekdaysOnly = false))
        assertEquals("weekly · remaining(4)", scheduleRepeatLabel(ctx, 7L * 24 * 3600_000L, 4, 0, weekdaysOnly = false))
        // 全部发完时剩 0
        assertEquals("daily · remaining(0)", scheduleRepeatLabel(ctx, 24L * 3600_000L, 3, 3, weekdaysOnly = false))
    }

    @Test
    fun `over sent occurrences coerce the remaining to zero`() {
        // occurrencesSent 超过 repeatCount 时不能出现负数
        assertEquals("daily · remaining(0)", scheduleRepeatLabel(ctx, 24L * 3600_000L, 2, 9, weekdaysOnly = false))
    }

    @Test
    fun `no repeat count means no remaining suffix`() {
        assertEquals("daily", scheduleRepeatLabel(ctx, 24L * 3600_000L, 0, 3, weekdaysOnly = false))
        // 负数 repeatCount 同样不拼接
        assertEquals("daily", scheduleRepeatLabel(ctx, 24L * 3600_000L, -2, 1, weekdaysOnly = false))
    }
}
