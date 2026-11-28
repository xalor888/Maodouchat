package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// fromWire reimplemented as a table lookup with a per-character
// ignore-case match (uppercaseChar/lowercaseChar per char, mirroring
// regionMatches): production matches with equals(ignoreCase = true).
// A plain lowercase() comparison would be wrong here: U+017F LONG S
// ('ſ') uppercases to 'S' but lowercases to itself, so "ſending" is
// SENDING for production (caught by the differential drive, audit leg)
// yet "ſending".lowercase() != "sending".
internal val STATUS_NAMES164 = listOf("SENDING", "SENT", "DELIVERED", "READ", "FAILED")

internal fun matchesIgnoreCase164(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (x == y) continue
        if (x.uppercaseChar() == y.uppercaseChar()) continue
        if (x.lowercaseChar() == y.lowercaseChar()) continue
        return false
    }
    return true
}

internal fun fromWireOracle164(value: String?): MessageStatus {
    if (value == null) return MessageStatus.SENT
    val name = STATUS_NAMES164.firstOrNull { matchesIgnoreCase164(value, it) } ?: return MessageStatus.SENT
    return MessageStatus.valueOf(name)
}

internal fun deliveryRankOracle164(status: MessageStatus): Int = when (status) {
    MessageStatus.SENDING -> 0
    MessageStatus.SENT -> 1
    MessageStatus.DELIVERED -> 2
    MessageStatus.READ -> 3
    MessageStatus.FAILED -> -1
}

internal fun higherDeliveryOracle164(a: MessageStatus, b: MessageStatus): MessageStatus {
    if (a == MessageStatus.FAILED) return b
    if (b == MessageStatus.FAILED) return a
    return if (deliveryRankOracle164(a) >= deliveryRankOracle164(b)) a else b
}

// ---------- generators ----------

internal val STATUS_POOL164: List<String?> = listOf(
    null, "", " ", "unknown", "not-a-status",
    "SENDING", "sending", "Sending", "SeNdInG",
    "SENT", "sent", "Sent", "sEnT",
    "DELIVERED", "delivered", "Delivered",
    "READ", "read", "Read", "rEaD",
    "FAILED", "failed", "Failed",
    "READ ", " SENT", "SENT ", "READ\t", "REA", "SEN", "FAIL",
    "SENDINGX", "XSENT", "READX", "DELIVEREDD", "FAILED1",
    "SENDING ", "DELIVERED ", "FAILED ",
    "send", "deliver", "read ", "rea d", "s ent",
    "ſending", "ſENT", "ſailed", "ſENDING",
)

internal const val STATUS_ALPHABET164 = "sentrdlivafSENT_ ſ"

internal fun randomStatusString164(rnd: Random): String {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 10)
    }
    val sb = StringBuilder()
    repeat(n) { sb.append(STATUS_ALPHABET164[rnd.nextInt(STATUS_ALPHABET164.length)]) }
    return sb.toString()
}

internal fun randomStatusCase164(rnd: Random): String? {
    return when (rnd.nextInt(10)) {
        0 -> null
        1 -> STATUS_POOL164[rnd.nextInt(STATUS_POOL164.size)]
        2 -> {
            val name = STATUS_NAMES164[rnd.nextInt(STATUS_NAMES164.size)]
            val sb = StringBuilder()
            for (ch in name) sb.append(if (rnd.nextBoolean()) ch.lowercaseChar() else ch)
            sb.toString()
        }
        else -> randomStatusString164(rnd)
    }
}

internal fun statusWireJson164(raw: String?): String {
    val sb = StringBuilder()
    sb.append("{")
    if (raw == null) {
        sb.append("\"raw\":null")
    } else {
        sb.append("\"raw\":\"")
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        val v = c.code
                        sb.append("0123456789abcdef"[v shr 12])
                        sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                        sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                        sb.append("0123456789abcdef"[v and 0xF])
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append("\"")
    }
    sb.append("}")
    return sb.toString()
}

class MessageStatusFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val cases = listOf(
            null to MessageStatus.SENT,
            "" to MessageStatus.SENT,
            " " to MessageStatus.SENT,
            "unknown" to MessageStatus.SENT,
            "not-a-status" to MessageStatus.SENT,
            "SENDING" to MessageStatus.SENDING,
            "sending" to MessageStatus.SENDING,
            "SeNdInG" to MessageStatus.SENDING,
            "SENT" to MessageStatus.SENT,
            "sent" to MessageStatus.SENT,
            "DELIVERED" to MessageStatus.DELIVERED,
            "delivered" to MessageStatus.DELIVERED,
            "READ" to MessageStatus.READ,
            "read" to MessageStatus.READ,
            "rEaD" to MessageStatus.READ,
            "FAILED" to MessageStatus.FAILED,
            "failed" to MessageStatus.FAILED,
            "READ " to MessageStatus.SENT,
            " SENT" to MessageStatus.SENT,
            "SENT " to MessageStatus.SENT,
            "READ\t" to MessageStatus.SENT,
            "REA" to MessageStatus.SENT,
            "FAIL" to MessageStatus.SENT,
            "SENDINGX" to MessageStatus.SENT,
            "XSENT" to MessageStatus.SENT,
            "s ent" to MessageStatus.SENT,
            "rea d" to MessageStatus.SENT,
            "ſending" to MessageStatus.SENDING,
            "ſENDING" to MessageStatus.SENDING,
            "ſENT" to MessageStatus.SENT,
            "ſailed" to MessageStatus.SENT,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, MessageStatus.fromWire(raw), "fromWire($raw)")
            assertEquals(expected, fromWireOracle164(raw), "oracle($raw)")
        }
        val ranks = mapOf(
            MessageStatus.SENDING to 0,
            MessageStatus.SENT to 1,
            MessageStatus.DELIVERED to 2,
            MessageStatus.READ to 3,
            MessageStatus.FAILED to -1,
        )
        for ((status, rank) in ranks) {
            assertEquals(rank, status.deliveryRank(), "deliveryRank($status)")
            assertEquals(rank, deliveryRankOracle164(status), "oracleRank($status)")
        }
        val all = MessageStatus.entries
        for (a in all) {
            for (b in all) {
                val expected = when {
                    a == MessageStatus.FAILED -> b
                    b == MessageStatus.FAILED -> a
                    ranks.getValue(a) >= ranks.getValue(b) -> a
                    else -> b
                }
                assertEquals(expected, higherDeliveryStatus(a, b), "higher($a,$b)")
                assertEquals(expected, higherDeliveryOracle164(a, b), "oracleHigher($a,$b)")
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(164002)
        repeat(4000) {
            val raw = randomStatusCase164(rnd)
            assertEquals(fromWireOracle164(raw), MessageStatus.fromWire(raw), "fromWire($raw)")
        }
        repeat(1000) {
            val a = MessageStatus.entries[rnd.nextInt(5)]
            val b = MessageStatus.entries[rnd.nextInt(5)]
            assertEquals(higherDeliveryOracle164(a, b), higherDeliveryStatus(a, b), "higher($a,$b)")
            assertEquals(deliveryRankOracle164(a), a.deliveryRank())
        }
    }

    @Test
    fun sweepExhaustive() {
        fun sweep(prefix: String, depth: Int) {
            if (depth == 0) {
                assertEquals(fromWireOracle164(prefix), MessageStatus.fromWire(prefix), "fromWire($prefix)")
                return
            }
            sweep(prefix, depth - 1)
            for (ch in STATUS_ALPHABET164) sweep(prefix + ch, depth - 1)
        }
        sweep("", 4)
        for (raw in STATUS_POOL164) {
            assertEquals(fromWireOracle164(raw), MessageStatus.fromWire(raw), "fromWire($raw)")
        }
        for (a in MessageStatus.entries) {
            for (b in MessageStatus.entries) {
                assertEquals(higherDeliveryOracle164(a, b), higherDeliveryStatus(a, b), "higher($a,$b)")
            }
        }
    }
}
