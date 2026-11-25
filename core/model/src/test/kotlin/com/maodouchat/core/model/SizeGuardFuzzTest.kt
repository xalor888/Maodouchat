// SizeGuard baseline / percent / report (143rd block). Oracle
// reimplements the three entry points from production text; the
// report layout is rebuilt line by line with the same JVM format
// primitives (%.2f / %-16s / %10d / %5.1f / %8d). Tests pin the
// default locale to US first: formatReport uses String.format,
// which follows the default locale.
package com.maodouchat.core.model

import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- size-guard oracles ----------

private fun isWithinBaselineOracle(report: ApkReport, baselineBytes: Long): Boolean =
    report.totalBytes <= baselineBytes

private fun percentOracle(report: ApkReport, bucket: SizeBucket): Double =
    if (report.totalBytes <= 0L) 0.0 else bucket.bytes * 100.0 / report.totalBytes

private fun formatReportOracle(report: ApkReport): String = buildString {
    val mb = report.totalBytes / (1024.0 * 1024.0)
    appendLine("=== Maodouchat Release APK 体积分解 ===")
    appendLine("APK   : ${report.apk.absolutePath}")
    appendLine("总大小: ${report.totalBytes} bytes (%.2f MB)".format(mb))
    appendLine("")
    for (bucket in report.buckets) {
        if (bucket.bytes <= 0L) continue
        appendLine(
            "  %-16s %10d bytes  (%5.1f%%)".format(bucket.label, bucket.bytes, percentOracle(report, bucket))
        )
    }
    appendLine("")
    if (report.largestSo.isEmpty()) {
        appendLine("  原生库: 无 .so（WebRTC 已排除，运行时自服下载）")
    } else {
        appendLine("  最大的 .so：")
        for ((name, size) in report.largestSo) {
            appendLine("    %8d bytes  %s".format(size, name))
        }
    }
}

// ---------- generators (mirrored by Oracle143 GEN, seed 143001) ----------

private val REPORT_PATH_POOL = arrayOf(
    "/tmp/a.apk", "/a/b.apk", "/tmp/sizeguard-fuzz/a.apk", "/x.apk",
    "/tmp/deep/dir/app-release.apk", "/tmp/app-release-unsigned.apk",
)
private val LABEL_POOL = arrayOf(
    "dex", "native(.so)", "resources(res/)", "assets", "META-INF", "other",
    "", "x", "a very long bucket label here", "é", "a b", "dex2",
    "1234567890123456", "12345678901234567",
)
private val SO_NAME_POOL = arrayOf(
    "lib/arm64-v8a/libx.so", "lib/x.so", "", "a.so", "é.so",
    "lib/armeabi-v7a/libverylongname.so",
)
private val TOTAL_POOL = longArrayOf(
    0L, 1L, -1L, 1000L, 1024L, 1048575L, 1048576L, 1048577L, 10485760L,
    104857600L, Long.MAX_VALUE, Long.MIN_VALUE, 250L, 524288L, 5242880L, 3L, 7L,
)
private val BYTES_POOL = longArrayOf(
    0L, 1L, -1L, 250L, -250L, 100L, 1048576L, Long.MAX_VALUE / 2, Long.MIN_VALUE / 2,
    3L, 7L, 33L, 999L,
)
private val BASELINE_POOL = longArrayOf(
    0L, 1L, -1L, 999L, 1000L, 10485760L, SizeGuard.DEFAULT_BASELINE_BYTES,
    Long.MAX_VALUE, Long.MIN_VALUE,
)

private fun randomLabel143(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 10)
    repeat(len) {
        when (rnd.nextInt(4)) {
            0 -> sb.append(('a' + rnd.nextInt(26)))
            1 -> sb.append(('0' + rnd.nextInt(10)))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            else -> sb.append(LABEL_POOL[rnd.nextInt(LABEL_POOL.size)])
        }
    }
    return sb.toString()
}

private fun randomLong143(rnd: Random): Long = when (rnd.nextInt(4)) {
    0 -> TOTAL_POOL[rnd.nextInt(TOTAL_POOL.size)]
    1 -> BYTES_POOL[rnd.nextInt(BYTES_POOL.size)]
    2 -> rnd.nextLong(0, 20_000_000L)
    else -> rnd.nextLong()
}

private fun genReport143(rnd: Random): ApkReport {
    val path = REPORT_PATH_POOL[rnd.nextInt(REPORT_PATH_POOL.size)]
    val total = randomLong143(rnd)
    val nBuckets = rnd.nextInt(0, 7)
    val buckets = List(nBuckets) {
        SizeBucket(
            if (rnd.nextInt(3) == 0) LABEL_POOL[rnd.nextInt(LABEL_POOL.size)] else randomLabel143(rnd),
            randomLong143(rnd),
        )
    }
    val nSo = rnd.nextInt(0, 9)
    val largestSo = List(nSo) {
        val name = if (rnd.nextInt(3) == 0) SO_NAME_POOL[rnd.nextInt(SO_NAME_POOL.size)] else randomLabel143(rnd) + ".so"
        name to randomLong143(rnd)
    }
    return ApkReport(File(path), total, buckets, largestSo)
}

private fun genBaseline143(rnd: Random): Long =
    if (rnd.nextInt(3) == 0) BASELINE_POOL[rnd.nextInt(BASELINE_POOL.size)]
    else rnd.nextLong()

private fun genPctBucket143(rnd: Random, report: ApkReport): SizeBucket =
    if (report.buckets.isNotEmpty() && rnd.nextInt(3) != 0) report.buckets[rnd.nextInt(report.buckets.size)]
    else SizeBucket(randomLabel143(rnd), randomLong143(rnd))

class SizeGuardFuzzTest {

    @Test
    fun pinnedBoundaries() {
        Locale.setDefault(Locale.US)
        fun report(total: Long, buckets: List<SizeBucket>, so: List<Pair<String, Long>> = emptyList()) =
            ApkReport(File("/tmp/a.apk"), total, buckets, so)
        val cases = listOf(
            report(1000L, listOf(SizeBucket("dex", 250L))),
            report(0L, listOf(SizeBucket("dex", 0L))),
            report(0L, emptyList()),
            report(-5L, listOf(SizeBucket("dex", 100L))),
            report(1048576L, listOf(SizeBucket("dex", 524288L))),
            report(1048575L, listOf(SizeBucket("dex", 524287L))),
            report(1048577L, listOf(SizeBucket("dex", 524289L))),
            report(5242880L, listOf(SizeBucket("dex", 2621440L))),
            report(Long.MAX_VALUE, listOf(SizeBucket("dex", Long.MAX_VALUE))),
            report(Long.MIN_VALUE, listOf(SizeBucket("dex", Long.MIN_VALUE))),
            report(1L, listOf(SizeBucket("dex", 1L))),
            report(3L, listOf(SizeBucket("dex", 1L))),
            report(7L, listOf(SizeBucket("dex", 1L))),
            report(1000L, listOf(SizeBucket("dex", 250L), SizeBucket("native(.so)", 0L), SizeBucket("assets", -10L))),
            report(1000L, listOf(SizeBucket("a very long bucket label here", 100L))),
            report(1000L, listOf(SizeBucket("", 100L))),
            report(1000L, listOf(SizeBucket("é", 100L))),
            report(1000L, listOf(SizeBucket("dex", 250L)), listOf("lib/arm64-v8a/libx.so" to 40L)),
            report(1000L, emptyList(), listOf("a.so" to 1L, "b.so" to 2L)),
            report(1000L, listOf(SizeBucket("dex", 250L)), (1..8).map { "lib$it.so" to it.toLong() }),
        )
        for (r in cases) {
            for (baseline in BASELINE_POOL) {
                assertEquals(isWithinBaselineOracle(r, baseline), SizeGuard.isWithinBaseline(r, baseline), "base total=${r.totalBytes} baseline=$baseline")
            }
            for (bucket in r.buckets + SizeBucket("dex", 250L) + SizeBucket("dex", 0L)) {
                assertEquals(percentOracle(r, bucket), r.percent(bucket), "pct total=${r.totalBytes} bytes=${bucket.bytes}")
            }
            assertEquals(formatReportOracle(r), SizeGuard.formatReport(r), "fmt total=${r.totalBytes}")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        Locale.setDefault(Locale.US)
        val rnd = Random(143001)
        repeat(1500) { i ->
            val report = genReport143(rnd)
            val baseline = genBaseline143(rnd)
            val bucket = genPctBucket143(rnd, report)
            assertEquals(isWithinBaselineOracle(report, baseline), SizeGuard.isWithinBaseline(report, baseline), "base iter=$i")
            assertEquals(percentOracle(report, bucket), report.percent(bucket), "pct iter=$i")
            assertEquals(formatReportOracle(report), SizeGuard.formatReport(report), "fmt iter=$i")
        }
    }

    @Test
    fun sweepExhaustive() {
        Locale.setDefault(Locale.US)
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa0"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        for (total in TOTAL_POOL) {
            for (bytes in BYTES_POOL) {
                val r = ApkReport(File("/tmp/a.apk"), total, listOf(SizeBucket("dex", bytes)), emptyList())
                assertEquals(formatReportOracle(r), SizeGuard.formatReport(r), "fmt sweep total=$total bytes=$bytes")
                assertEquals(percentOracle(r, SizeBucket("dex", bytes)), r.percent(SizeBucket("dex", bytes)), "pct sweep total=$total bytes=$bytes")
            }
        }
        for (base in listOf("dex", "native(.so)", "a very long bucket label here", "é")) {
            for (label in editsOf(base)) {
                val r = ApkReport(File("/tmp/a.apk"), 1000L, listOf(SizeBucket(label, 250L)), emptyList())
                assertEquals(formatReportOracle(r), SizeGuard.formatReport(r), "fmt label sweep $label")
            }
        }
        for (code in 0x00..0x7F) {
            val label = String(Character.toChars(code))
            val r = ApkReport(File("/tmp/a.apk"), 1000L, listOf(SizeBucket(label, 250L)), emptyList())
            assertEquals(formatReportOracle(r), SizeGuard.formatReport(r), "single-unit label code=$code")
            val r2 = ApkReport(File("/tmp/a.apk"), 1000L, listOf(SizeBucket("dex", 250L)), listOf(label to 100L))
            assertEquals(formatReportOracle(r2), SizeGuard.formatReport(r2), "single-unit so code=$code")
        }
    }
}
