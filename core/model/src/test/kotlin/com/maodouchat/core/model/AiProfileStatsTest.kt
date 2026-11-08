package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiProfileStatsTest {

    private fun message(id: String, content: String, timestamp: Long) =
        Message(id = id, chatId = "c1", senderId = "u1", content = content, timestamp = timestamp)

    /** Local-time timestamp for today at the given hour (robust to the runner TZ). */
    private fun todayAt(hour: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun daysAgo(days: Int, hour: Int): Long =
        java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -days)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun emptyMessagesReturnEmptyStats() {
        assertEquals(LocalStats(), AiProfileStats.computeStats(emptyList()))
    }

    @Test
    fun countsMessagesAndActiveDays() {
        val stats = AiProfileStats.computeStats(
            listOf(
                message("m1", "hello world", daysAgo(2, 10)),
                message("m2", "hello again", daysAgo(1, 10)),
                message("m3", "hello today", todayAt(10)),
            )
        )
        assertEquals(3, stats.messageCount)
        assertEquals(3, stats.activeDays)
    }

    @Test
    fun bucketsSplitByLocalHour() {
        val stats = AiProfileStats.computeStats(
            listOf(
                message("m1", "morning note", todayAt(10)),
                message("m2", "afternoon note", todayAt(14)),
                message("m3", "evening note", todayAt(20)),
                message("m4", "night note", todayAt(2)),
            )
        )
        assertEquals(1, stats.morning)
        assertEquals(1, stats.afternoon)
        assertEquals(1, stats.evening)
        assertEquals(1, stats.night)
    }

    @Test
    fun emojiCountedAndPlainTextIsZero() {
        val plain = AiProfileStats.computeStats(listOf(message("m1", "plain text", todayAt(10))))
        assertEquals(0, plain.emojiCount)
        val withEmoji = AiProfileStats.computeStats(listOf(message("m2", "hi \uD83D\uDE00", todayAt(10))))
        assertTrue(withEmoji.emojiCount > 0)
    }

    @Test
    fun topTermsRankCjkBigramsAndLatinWords() {
        // 拉丁词用标点分隔：collectTerms 先去空白再按非字母数字切分，空格不分词，
        // 所以 "the quick" 会粘成 thequick。
        val cjk = AiProfileStats.computeStats(
            listOf(message("m1", "\u4F60\u597D\u4E16\u754C\u4F60\u597D", todayAt(10)))
        )
        assertEquals("\u4F60\u597D", cjk.topTerms.first())
        val latin = AiProfileStats.computeStats(
            listOf(message("m2", "hello, world! hello; the quick.", todayAt(10)))
        )
        assertEquals(listOf("hello", "thequick", "world"), latin.topTerms)
        assertTrue("the" !in latin.topTerms)
    }
}
