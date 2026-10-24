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
        QuietWindow(enabled = enabled, startMinute = start, endMinute = end)

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
    ) = AnnouncementData(
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

    @Test
    fun `announcement window boundaries are inclusive`() {
        // 生效窗口两端都含入：startsAt==now 与 expiresAt==now 的公告都保留。
        val edge = announcement("edge", startsAt = 1000L, expiresAt = 1000L)
        assertEquals(listOf("edge"), AnnouncementPolicy.filterForDisplay(listOf(edge), 1000L).map { it.id })
    }

    @Test
    fun `shouldNotifyNow is false on empty list`() {
        assertFalse(AnnouncementPolicy.shouldNotifyNow(emptyList(), 10))
    }

    @Test
    fun `forceShowAcked still drops expired`() {
        // forceShowAcked 只忽略已读，不放宽生效窗口。
        val old = announcement("old", acked = true, expiresAt = 5L)
        assertEquals(
            0,
            AnnouncementPolicy.filterForDisplay(listOf(old), 10L, forceShowAcked = true).size
        )
    }

    @Test
    fun `level priority map is frozen`() {
        assertEquals(
            mapOf("EMERGENCY" to 0, "MAINTENANCE" to 1, "WARNING" to 2, "INFO" to 3),
            AnnouncementPolicy.LEVEL_PRIORITY
        )
    }

    @Test
    fun `full day window suppresses every minute except end`() {
        val w = window(start = 0, end = 1439)
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 0))
        assertTrue(ChatQuietHoursPolicy.shouldSuppress(w, 1438))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(w, 1439))
    }

    @Test
    fun `clamped equal minutes never suppress`() {
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = 1500, end = 1500), 1439))
        assertFalse(ChatQuietHoursPolicy.shouldSuppress(window(start = -5, end = -5), 0))
    }

    @Test
    fun `parse payload filters and sorts`() {
        val raw = """{"announcements":[
            {"id":"info","title":"t","content":"c","level":"INFO","startsAt":5,"expiresAt":9999,"status":"ACTIVE","acked":false},
            {"id":"emer","title":"t","content":"c","level":"EMERGENCY","startsAt":10,"expiresAt":9999,"status":"ACTIVE","acked":false},
            {"id":"bad","title":"t","content":"c","level":"WEIRD","startsAt":0,"expiresAt":9999,"status":"ACTIVE","acked":false},
            {"id":"","title":"t"}
        ]}"""
        val kept = AnnouncementPolicy.parseAndFilterActivePayload(raw, 100L)
        assertEquals(listOf("emer", "info"), kept.map { it.id })
    }

    @Test
    fun `parse payload tolerates malformed input`() {
        assertEquals(emptyList<AnnouncementData>(), AnnouncementPolicy.parseAndFilterActivePayload("not json", 100L))
        assertEquals(emptyList<AnnouncementData>(), AnnouncementPolicy.parseAndFilterActivePayload("", 100L))
        assertEquals(emptyList<AnnouncementData>(), AnnouncementPolicy.parseAndFilterActivePayload("{}", 100L))
    }

    @Test
    fun `acked emergency never notifies`() {
        // 已读的高优先级公告不触发前台提示。
        assertFalse(
            "已读紧急公告不应触发通知",
            AnnouncementPolicy.shouldNotifyNow(listOf(announcement("e", level = "EMERGENCY", acked = true)), 10)
        )
    }
}
