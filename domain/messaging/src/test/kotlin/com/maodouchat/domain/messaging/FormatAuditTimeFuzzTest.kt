// Fuzz / random-oracle tests for formatAuditTime
// (checklist line 931, 126th block). Test-only, zero production changes.
// The oracle is hand-written with java.time -- an independent path from
// the production SimpleDateFormat("M/d HH:mm"). Pinned cases compare
// against the oracle instead of wall-clock literals: the output depends
// on the JVM default timezone (CI runs UTC, local runs Shanghai), so a
// literal like "1/1 08:00" would only hold in one zone.
package com.maodouchat.domain.messaging

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatAuditTimeFuzzTest {

// ---------- oracle ----------

// java.time reimplementation of SimpleDateFormat("M/d HH:mm") -- month
// and day unpadded, hour and minute zero-padded. Same system zone as
// production, so the two agree in any zone. The tested locales all
// render ASCII digits (probe126 verified US/CHINA/UK/ROOT/default).
private fun formatAuditTimeOracle(timestamp: Long): String {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    val hh = if (t.hour < 10) "0" + t.hour else "" + t.hour
    val mm = if (t.minute < 10) "0" + t.minute else "" + t.minute
    return "" + t.monthValue + "/" + t.dayOfMonth + " " + hh + ":" + mm
}

// Python datetime only covers year 1..9999; latest local instant is
// 9999-12-31T23:59:59.999+08:00. The lower bound is 1901-01-01: the
// tzdb LMT offset (+08:05:43) ends there, and production's
// SimpleDateFormat (java.util.TimeZone) applies raw +08:00 to every
// pre-1901 instant while java.time/Python apply LMT -- both oracles
// agree with each other there and disagree with production, so the
// generator stays at/after 1901.
private val AUD_TS_MIN = -2208988800000L
private val AUD_TS_MAX = 253402271999999L

private val AUD_EDGE_POOL = longArrayOf(
    0L, -1L, 1L, -60000L, 60000L, -86400000L, 86400000L,
    1709136000000L, 1709135999000L, 1709222400000L, 1709222399000L,
    1709168700000L, 1767196799000L, 1767196800000L, 1577808000000L,
    1893427200000L, AUD_TS_MIN, AUD_TS_MAX,
    576000000000L, 576000000001L, 575999999999L, // 1988-04-03, a week before DST start
    576612000000L, 589914000000L, // 1988 DST transitions (Apr 10 +08->+09, Sep 11 +09->+08)
    946684800000L, 946684799999L // 2000-01-01 boundary
)

private fun genAudTs(rnd: Random): Long = when (rnd.nextInt(4)) {
    0 -> AUD_EDGE_POOL[rnd.nextInt(AUD_EDGE_POOL.size)]
    1 -> rnd.nextLong(AUD_TS_MIN, AUD_TS_MIN + 100000000000L)
    2 -> rnd.nextLong(AUD_TS_MAX - 100000000000L, AUD_TS_MAX + 1)
    else -> rnd.nextLong(AUD_TS_MIN, AUD_TS_MAX + 1)
}

private val AUD_SHAPE = Regex("^\\d{1,2}/\\d{1,2} \\d{2}:\\d{2}$")

    @Test
    fun pinnedBoundaries() {
        // Fixed inputs, oracle-computed expectations (see the header
        // note on why no wall-clock literals): epoch, negative,
        // leap day 2024-02-29, year boundaries, 1988 DST transitions,
        // and the 1901 LMT-era boundary itself.
        for (ts in longArrayOf(
            0L, -1L, -60000L, 60000L,
            1709136000000L, 1709222399000L, 1709168700000L,
            1767196799000L, 1767196800000L, 1577808000000L, 1893427200000L,
            576612000000L, 589914000000L,
            AUD_TS_MIN, AUD_TS_MIN + 1, AUD_TS_MAX, AUD_TS_MAX - 1,
            946684800000L, 946684799999L
        )) {
            assertEquals(formatAuditTimeOracle(ts), formatAuditTime(ts, Locale.US))
        }
        // Shape holds for every locale on the same inputs, and the
        // four ASCII-digit locales agree with each other exactly.
        for (ts in longArrayOf(0L, 1709168700000L, 576612000000L, AUD_TS_MAX)) {
            assertTrue(AUD_SHAPE.matches(formatAuditTime(ts, Locale.US)))
            val us = formatAuditTime(ts, Locale.US)
            assertEquals(us, formatAuditTime(ts, Locale.CHINA))
            assertEquals(us, formatAuditTime(ts, Locale.UK))
            assertEquals(us, formatAuditTime(ts, Locale.ROOT))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(126001)
        repeat(1500) {
            val ts = genAudTs(rnd)
            val locale = when (rnd.nextInt(5)) {
                0 -> Locale.US
                1 -> Locale.CHINA
                2 -> Locale.UK
                3 -> Locale.ROOT
                else -> Locale.getDefault()
            }
            assertEquals(formatAuditTimeOracle(ts), formatAuditTime(ts, locale))
        }
    }

    @Test
    fun edgePoolSweep() {
        // Every edge timestamp in every locale, plus every minute
        // over two days from the epoch (locale US).
        for (ts in AUD_EDGE_POOL) {
            for (locale in listOf(Locale.US, Locale.CHINA, Locale.UK, Locale.ROOT, Locale.getDefault())) {
                assertEquals(formatAuditTimeOracle(ts), formatAuditTime(ts, locale))
            }
        }
        for (minute in 0..2 * 24 * 60) {
            val ts = minute * 60000L
            assertEquals(formatAuditTimeOracle(ts), formatAuditTime(ts, Locale.US))
        }
    }
}
