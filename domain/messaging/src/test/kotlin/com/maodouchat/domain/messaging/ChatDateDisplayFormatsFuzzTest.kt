// ChatDateDisplay format functions (141st block); oracle uses
// SimpleDateFormat with the same probed patterns in the same JVM.
// isSameDay / isYesterday / dateLabelBucket / formatHourMinute were
// covered by the 102nd block.
package com.maodouchat.domain.messaging

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private val CD_LOCALES = listOf(
    Locale.US, Locale.UK, Locale.CHINA, Locale.TAIWAN,
    Locale.JAPAN, Locale.GERMANY, Locale.FRANCE, Locale.KOREA,
)

private fun localeTagOracle(locale: Locale): String = when (locale) {
    Locale.US -> "en_US"
    Locale.UK -> "en_GB"
    Locale.CHINA -> "zh_CN"
    Locale.TAIWAN -> "zh_TW"
    Locale.JAPAN -> "ja_JP"
    Locale.GERMANY -> "de_DE"
    Locale.FRANCE -> "fr_FR"
    Locale.KOREA -> "ko_KR"
    else -> "en_US"
}

private fun dtPatternOracle(locale: Locale): String = when (localeTagOracle(locale)) {
    "en_US" -> "MMM d, y, h:mm a"
    "en_GB" -> "d MMM y, HH:mm"
    "zh_CN", "zh_TW" -> "y年M月d日 ah:mm"
    "ja_JP" -> "y/MM/dd H:mm"
    "de_DE" -> "dd.MM.y, HH:mm"
    "fr_FR" -> "d MMM y HH:mm"
    "ko_KR" -> "y. M. d. a h:mm"
    else -> "MMM d, y, h:mm a"
}

private fun mdPatternOracle(locale: Locale): String = when (localeTagOracle(locale)) {
    "en_US" -> "MMM d, y"
    "en_GB" -> "d MMM y"
    "zh_CN", "zh_TW" -> "y年M月d日"
    "ja_JP" -> "y/MM/dd"
    "de_DE" -> "dd.MM.y"
    "fr_FR" -> "d MMM y"
    "ko_KR" -> "y. M. d."
    else -> "MMM d, y"
}

private fun formatChatDateTimeOracle(timestamp: Long, locale: Locale): String =
    SimpleDateFormat(dtPatternOracle(locale), locale).format(Date(timestamp))

private fun formatMediumDateOracle(timestamp: Long, locale: Locale): String =
    SimpleDateFormat(mdPatternOracle(locale), locale).format(Date(timestamp))

private fun formatMonthDayTimeOracle(timestamp: Long, locale: Locale): String =
    SimpleDateFormat("MM-dd HH:mm", locale).format(Date(timestamp))

private fun calMs141(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0, second: Int = 0, ms: Int = 0): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
        set(Calendar.MILLISECOND, ms)
    }.timeInMillis

private fun genCdCase(rnd: Random): Pair<Long, Locale> {
    val base = 1577836800000L + rnd.nextLong(0L, 8L * 365 * 86400000L)
    val delta = rnd.nextLong(-30L * 86400000L, 30L * 86400000L + 1)
    return Pair(base + delta, CD_LOCALES[rnd.nextInt(CD_LOCALES.size)])
}

private fun genCdLine(rnd: Random): String {
    val (ts, locale) = genCdCase(rnd)
    return "cd:" + ts + ":" + localeTagOracle(locale)
}

class ChatDateDisplayFormatsFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            Triple(0L, Locale.US, "epoch"),
            Triple(-1000L, Locale.CHINA, "before epoch"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.US, "nov 15"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.CHINA, "nov 15 zh"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.JAPAN, "nov 15 ja"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.GERMANY, "nov 15 de"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.FRANCE, "nov 15 fr"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.KOREA, "nov 15 ko"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.UK, "nov 15 gb"),
            Triple(calMs141(2023, 11, 15, 6, 13), Locale.TAIWAN, "nov 15 tw"),
            Triple(calMs141(2024, 2, 29, 23, 59), Locale.US, "leap day"),
            Triple(calMs141(2024, 1, 1, 0, 0), Locale.US, "new year midnight"),
            Triple(calMs141(2026, 12, 31, 23, 59), Locale.US, "year end"),
            Triple(calMs141(2023, 1, 5, 0, 5), Locale.US, "single digit day"),
            Triple(calMs141(2023, 1, 5, 0, 5), Locale.FRANCE, "single digit day fr"),
            Triple(calMs141(2023, 1, 5, 0, 5), Locale.GERMANY, "single digit day de"),
            Triple(calMs141(2023, 12, 25, 13, 30), Locale.US, "pm hour"),
            Triple(calMs141(2023, 12, 25, 0, 30), Locale.US, "am hour"),
            Triple(calMs141(2023, 12, 25, 12, 0), Locale.US, "noon"),
            Triple(calMs141(2023, 12, 25, 12, 0), Locale.CHINA, "noon zh"),
            Triple(calMs141(2023, 12, 25, 12, 0), Locale.KOREA, "noon ko"),
        )
        for ((ts, locale, label) in cases) {
            assertEquals(formatChatDateTimeOracle(ts, locale), ChatDateDisplay.formatChatDateTime(ts, locale), "dt $label")
            assertEquals(formatMediumDateOracle(ts, locale), ChatDateDisplay.formatMediumDate(ts, locale), "md $label")
            assertEquals(formatMonthDayTimeOracle(ts, locale), ChatDateDisplay.formatMonthDayTime(ts, locale), "mdt $label")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(141002)
        repeat(1500) { i ->
            val (ts, locale) = genCdCase(rnd)
            assertEquals(formatChatDateTimeOracle(ts, locale), ChatDateDisplay.formatChatDateTime(ts, locale), "dt iter=$i ts=$ts loc=${localeTagOracle(locale)}")
            assertEquals(formatMediumDateOracle(ts, locale), ChatDateDisplay.formatMediumDate(ts, locale), "md iter=$i ts=$ts loc=${localeTagOracle(locale)}")
            assertEquals(formatMonthDayTimeOracle(ts, locale), ChatDateDisplay.formatMonthDayTime(ts, locale), "mdt iter=$i ts=$ts loc=${localeTagOracle(locale)}")
        }
    }

    @Test
    fun sweepExhaustive() {
        for (locale in CD_LOCALES) {
            val day0 = calMs141(2024, 1, 1, 12)
            for (d in 0 until 731) {
                val ts = day0 + d * 86400000L
                assertEquals(formatChatDateTimeOracle(ts, locale), ChatDateDisplay.formatChatDateTime(ts, locale), "dt day=$d loc=${localeTagOracle(locale)}")
                assertEquals(formatMediumDateOracle(ts, locale), ChatDateDisplay.formatMediumDate(ts, locale), "md day=$d loc=${localeTagOracle(locale)}")
                assertEquals(formatMonthDayTimeOracle(ts, locale), ChatDateDisplay.formatMonthDayTime(ts, locale), "mdt day=$d loc=${localeTagOracle(locale)}")
            }
            for (m in 0 until 60) {
                val ts = calMs141(2023, 11, 15, 0, 0) + m * 60000L
                assertEquals(formatChatDateTimeOracle(ts, locale), ChatDateDisplay.formatChatDateTime(ts, locale), "dt minute=$m loc=${localeTagOracle(locale)}")
            }
            for (h in 0 until 24) {
                val ts = calMs141(2023, 11, 15, h, 30)
                assertEquals(formatChatDateTimeOracle(ts, locale), ChatDateDisplay.formatChatDateTime(ts, locale), "dt hour=$h loc=${localeTagOracle(locale)}")
            }
        }
    }
}
