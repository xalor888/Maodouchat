package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr194d(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun decStr194d(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

// String.compareTo 的独立重实现：逐 UTF-16 码元比，首个不同码元之差；前缀短者小。
private fun stringCompare194d(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// compareForDelivery 的独立重实现：epoch → sequence → timestamp → id。
private fun cmpOracle194d(
    epochA: Long, sequenceA: Long, timestampA: Long, idA: String,
    epochB: Long, sequenceB: Long, timestampB: Long, idB: String,
): Int {
    val byEpoch = epochA.compareTo(epochB)
    if (byEpoch != 0) return byEpoch
    val bySeq = sequenceA.compareTo(sequenceB)
    if (bySeq != 0) return bySeq
    val byTs = timestampA.compareTo(timestampB)
    if (byTs != 0) return byTs
    return stringCompare194d(idA, idB)
}

private fun cmpProd194d(
    epochA: Long, sequenceA: Long, timestampA: Long, idA: String,
    epochB: Long, sequenceB: Long, timestampB: Long, idB: String,
): Int = CallSignalingOrderPolicy.compareForDelivery(epochA, sequenceA, timestampA, idA, epochB, sequenceB, timestampB, idB)

private data class DeliveryPair194d(
    val epochA: Long, val sequenceA: Long, val timestampA: Long, val idA: String,
    val epochB: Long, val sequenceB: Long, val timestampB: Long, val idB: String,
)

private val DEL_LONGS194d = listOf(
    0L, 1L, -1L, 2L, -2L, 1000L, Long.MAX_VALUE, Long.MIN_VALUE,
    Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 86400000L
)

private val DEL_IDS194d = listOf(
    "", "a", "b", "aa", "ab", "a/b", "A", "a", "id-1", "id-2", "\u00e9", "\u4e2d\u6587",
    "\ud83d\ude00", "\uFFFF", "\uD800\uDC00", "\uD800\uDC01", "a\uFFFF", "a\ud83d\ude00",
    "x".repeat(40), "0", "-1"
)

private val DEL_RANDOM_CHARS194d = listOf("a", "b", "1", "2", "-", "_", "/", "\u00e9", "\ud83d\ude00", "\uFFFF")

private fun randomDelId194d(r: Random): String =
    if (r.nextInt(3) == 0) DEL_IDS194d[r.nextInt(DEL_IDS194d.size)]
    else (0 until r.nextInt(8)).joinToString("") { DEL_RANDOM_CHARS194d[r.nextInt(DEL_RANDOM_CHARS194d.size)] }

private fun randomDelLong194d(r: Random): Long =
    if (r.nextInt(3) == 0) DEL_LONGS194d[r.nextInt(DEL_LONGS194d.size)]
    else r.nextLong(-1000, 2_000_000_000)

private fun randomDeliveryPair194d(r: Random): DeliveryPair194d {
    // 一半概率让一侧与另一侧共享前缀字段，迫使比较走到更深的 tie-break。
    val sharedEpoch = r.nextBoolean()
    val sharedSeq = r.nextBoolean()
    val sharedTs = r.nextBoolean()
    val epochA = randomDelLong194d(r)
    val seqA = randomDelLong194d(r)
    val tsA = randomDelLong194d(r)
    val idA = randomDelId194d(r)
    return DeliveryPair194d(
        epochA, seqA, tsA, idA,
        if (sharedEpoch) epochA else randomDelLong194d(r),
        if (sharedSeq) seqA else randomDelLong194d(r),
        if (sharedTs) tsA else randomDelLong194d(r),
        if (r.nextInt(3) == 0) idA else randomDelId194d(r)
    )
}

private fun boundaryDeliveryPair194d(r: Random): DeliveryPair194d {
    val idA = DEL_IDS194d[r.nextInt(DEL_IDS194d.size)]
    val idB = when (r.nextInt(5)) {
        0 -> idA
        1 -> idA + "x"
        2 -> idA.dropLast(1)
        3 -> "\uFFFF"
        else -> "\uD800\uDC00"
    }
    val base = DEL_LONGS194d[r.nextInt(DEL_LONGS194d.size)]
    return DeliveryPair194d(base, base, base, idA, base, base, base, idB)
}

// ---------- tests ----------

class CallSignalingDeliveryFuzzTest {

    @Test
    fun delDifferential194() {
        val r = Random(194005)
        repeat(300) {
            val p = randomDeliveryPair194d(r)
            assertEquals(
                cmpOracle194d(p.epochA, p.sequenceA, p.timestampA, p.idA, p.epochB, p.sequenceB, p.timestampB, p.idB),
                cmpProd194d(p.epochA, p.sequenceA, p.timestampA, p.idA, p.epochB, p.sequenceB, p.timestampB, p.idB)
            )
        }
    }

    @Test
    fun delBoundaryDifferential194() {
        val r = Random(194006)
        repeat(300) {
            val p = boundaryDeliveryPair194d(r)
            assertEquals(
                cmpOracle194d(p.epochA, p.sequenceA, p.timestampA, p.idA, p.epochB, p.sequenceB, p.timestampB, p.idB),
                cmpProd194d(p.epochA, p.sequenceA, p.timestampA, p.idA, p.epochB, p.sequenceB, p.timestampB, p.idB)
            )
        }
    }

    @Test
    fun delPinned194() {
        // 全相等为 0；各级 tie-break 各走一层。
        assertEquals(0, cmpProd194d(1L, 2L, 3L, "a", 1L, 2L, 3L, "a"))
        assertEquals(-1, cmpProd194d(1L, 2L, 3L, "a", 2L, 2L, 3L, "a"))
        assertEquals(1, cmpProd194d(2L, 2L, 3L, "a", 1L, 2L, 3L, "a"))
        assertEquals(1, cmpProd194d(1L, 3L, 3L, "a", 1L, 2L, 3L, "a"))
        assertEquals(-1, cmpProd194d(1L, 2L, 3L, "a", 1L, 2L, 4L, "a"))
        // id 比较返回首个不同码元之差，不是归一化的 ±1。
        assertEquals(1, cmpProd194d(1L, 2L, 3L, "b", 1L, 2L, 3L, "a"))
        assertEquals(-2, cmpProd194d(1L, 2L, 3L, "a", 1L, 2L, 3L, "c"))
        assertEquals(1, cmpProd194d(1L, 2L, 3L, "ab", 1L, 2L, 3L, "a"))
        assertEquals(-1, cmpProd194d(1L, 2L, 3L, "", 1L, 2L, 3L, "a"))
        assertEquals(0, cmpOracle194d(1L, 2L, 3L, "a", 1L, 2L, 3L, "a"))
        // 星号陷阱：按 UTF-16 码元比，U+FFFF（0xFFFF）大于 U+10000 的高代理（0xD800）；
        // 按码点比会得出相反结论，oracle 必须逐码元。
        assertEquals(0xFFFF - 0xD800, cmpProd194d(1L, 2L, 3L, "\uFFFF", 1L, 2L, 3L, "\uD800\uDC00"))
        assertEquals(0xFFFF - 0xD800, cmpOracle194d(1L, 2L, 3L, "\uFFFF", 1L, 2L, 3L, "\uD800\uDC00"))
        // Long 边界不溢出：MAX 与 MIN 只差符号位比较结果。
        assertEquals(1, cmpProd194d(Long.MAX_VALUE, 0L, 0L, "", Long.MIN_VALUE, 0L, 0L, ""))
        assertEquals(-1, cmpProd194d(Long.MIN_VALUE, 0L, 0L, "", Long.MAX_VALUE, 0L, 0L, ""))
    }
}
