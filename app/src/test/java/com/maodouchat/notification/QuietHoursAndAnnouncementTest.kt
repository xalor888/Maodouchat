package com.maodouchat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免打扰时段与公告展示策略契约测试（9.232）。
 */
class QuietHoursAndAnnouncementTest {

    private fun window(enabled: Boolean = true, start: Int = 0, end: Int = 0) =
        ChatQuietHoursStore.QuietWindow(enabled = enabled, startMinute = start, endMinute = end)

    @Test
    fun `quiet hours suppress inside same day window`() {
        val w = window(start = 22 * 60, end = 23 * 60)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 22 * 60))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 22 * 60 + 30))
        // 半开区间：end 分钟本身不抑制
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 23 * 60))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 12 * 60))
    }

    @Test
    fun `quiet hours wrap across midnight`() {
        val w = window(start = 23 * 60, end = 7 * 60)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 23 * 60 + 30))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 6 * 60 + 59))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 7 * 60))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 12 * 60))
    }

    @Test
    fun `quiet hours disabled or zero length never suppress`() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(enabled = false, start = 0, end = 1439), 12 * 60))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = 9 * 60, end = 9 * 60), 9 * 60))
    }

    @Test
    fun `quiet hours clamps out of range input`() {
        val w = window(start = -100, end = 2000)
        // start 钳到 0，end 钳到 1439：同日窗口 [0,1439)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 1439))
        // 负分钟钳到 0：窗口 [10,20) 不含 0，窗口 [0,20) 含 0
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = 10, end = 20), -5))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(window(start = 0, end = 20), -5))
    }

    private fun announcement(
        id: String,
        level: String = "INFO",
        startsAt: Long = 0L,
        expiresAt: Long = Long.MAX_VALUE,
        status: String = "ACTIVE",
        acked: Boolean = false
    ) = AnnouncementPolicy.AnnouncementData(
        id = id, title = "t$id", content = "c$id",
        level = level, startsAt = startsAt, expiresAt = expiresAt, status = status, acked = acked
    )

    @Test
    fun `announcement filter drops acked expired inactive and unknown level`() {
        val now = 1_000L
        val list = listOf(
            announcement("ok"),
            announcement("acked", acked = true),
            announcement("expired", expiresAt = 999L),
            announcement("future", startsAt = 1001L),
            announcement("inactive", status = "DRAFT"),
            announcement("badlevel", level = "WEIRD")
        )
        val kept = AnnouncementPolicy.filterForDisplay(list, now)
        assertEquals(listOf("ok"), kept.map { it.id })
    }

    @Test
    fun `announcement force show ignores acked`() {
        val list = listOf(announcement("a", acked = true))
        assertEquals(0, AnnouncementPolicy.filterForDisplay(list, 10).size)
        assertEquals(1, AnnouncementPolicy.filterForDisplay(list, 10, forceShowAcked = true).size)
    }

    @Test
    fun `announcement sorted by level priority then start`() {
        val list = listOf(
            announcement("info", level = "INFO", startsAt = 1),
            announcement("emer2", level = "EMERGENCY", startsAt = 5),
            announcement("emer1", level = "EMERGENCY", startsAt = 2),
            announcement("warn", level = "WARNING", startsAt = 0)
        )
        val sorted = AnnouncementPolicy.filterForDisplay(list, 100)
        assertEquals(listOf("emer1", "emer2", "warn", "info"), sorted.map { it.id })
    }

    @Test
    fun `should notify only for emergency or maintenance`() {
        assertTrue(AnnouncementPolicy.shouldNotifyNow(listOf(announcement("e", level = "EMERGENCY")), 10))
        assertTrue(AnnouncementPolicy.shouldNotifyNow(listOf(announcement("m", level = "MAINTENANCE")), 10))
        assertFalse(AnnouncementPolicy.shouldNotifyNow(listOf(announcement("w", level = "WARNING")), 10))
        // 过期的高优先级不触发
        assertFalse(AnnouncementPolicy.shouldNotifyNow(listOf(announcement("e", level = "EMERGENCY", expiresAt = 5)), 10))
    }
}
