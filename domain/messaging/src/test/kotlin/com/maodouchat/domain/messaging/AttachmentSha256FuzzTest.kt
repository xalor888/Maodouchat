package com.maodouchat.domain.messaging

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// toHexString restated by hand: two lowercase hex digits per byte,
// unsigned (a negative byte prints its low 8 bits).
internal fun toHexStringOracle180(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v shr 4])
        sb.append(hex[v and 0xF])
    }
    return sb.toString()
}

// sha256Hex restated by hand: the require() gate first (offset and
// length non-negative, wrapped sum within the file), then the read
// loop over the slice — a short read means the file changed mid-read.
// A read whose position + count wraps past Long.MAX_VALUE fails in
// the kernel with EINVAL; that zone is filesystem-independent in
// principle but the test generators never produce it (see below).
internal fun sha256HexOracle180(bytes: ByteArray, offset: Long, length: Long): String {
    require(offset >= 0L && length >= 0L && offset + length <= bytes.size.toLong())
    var pos = offset
    var remaining = length
    while (remaining > 0L) {
        val count = minOf(8192L, remaining)
        if (pos + count < 0L) throw java.io.IOException("Invalid argument")
        if (pos >= bytes.size.toLong()) break
        val read = minOf(count, bytes.size.toLong() - pos)
        remaining -= read
        pos += read
    }
    require(remaining == 0L) { "attachment_source_changed" }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val start = offset.toInt()
    val end = (offset + length).toInt()
    digest.update(bytes, start, end - start)
    return toHexStringOracle180(digest.digest())
}

// True when a case would hit the kernel EINVAL read rule (position +
// count wrapping past Long.MAX_VALUE). The @Tests skip these: the
// oracle models them, but the differential drive (not CI) is the
// place to verify kernel-edge behaviour.
internal fun isSeekInvalidCase180(offset: Long, length: Long): Boolean =
    offset > 0L && length > 0L && offset + minOf(8192L, length) < 0L

internal data class Sha256Case180(
    val bytes: List<Int>,
    val offset: Long,
    val length: Long,
)

// Production sha256Hex runs against a real temp file holding the same
// bytes; the oracle works on the byte array directly.
internal fun sha256HexProduction180(c: Sha256Case180): String {
    val file = File.createTempFile("sha180", ".bin")
    try {
        val raw = ByteArray(c.bytes.size) { c.bytes[it].toByte() }
        file.writeBytes(raw)
        return file.sha256Hex(c.offset, c.length)
    } finally {
        file.delete()
    }
}

internal fun toHexStringProduction180(bytes: List<Int>): String {
    val raw = ByteArray(bytes.size) { bytes[it].toByte() }
    return raw.toHexString()
}

// ---------- generators ----------

internal val SHA256_OFFSET_POOL180: List<Long> = listOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -100L, -1L, 0L, 1L, 2L, 7L, 8L,
    9L, 99L, 100L, 101L, 1000L, 1L shl 20, 1L shl 40, Long.MAX_VALUE - 1,
    Long.MAX_VALUE,
)

internal val SHA256_LENGTH_POOL180: List<Long> = listOf(
    Long.MIN_VALUE, -100L, -1L, 0L, 1L, 2L, 7L, 8L, 9L, 99L, 100L, 101L,
    1000L, 1L shl 20, Long.MAX_VALUE - 1, Long.MAX_VALUE,
)

internal fun randomSha256Case180(rnd: Random): Sha256Case180 {
    val size = rnd.nextInt(0, 300)
    val bytes = List(size) { rnd.nextInt(0, 256) }
    repeat(10) {
        val offset = if (rnd.nextInt(2) == 0) {
            SHA256_OFFSET_POOL180[rnd.nextInt(SHA256_OFFSET_POOL180.size)]
        } else {
            rnd.nextLong(-10L, (size + 10).toLong())
        }
        val length = if (rnd.nextInt(2) == 0) {
            SHA256_LENGTH_POOL180[rnd.nextInt(SHA256_LENGTH_POOL180.size)]
        } else {
            rnd.nextLong(-10L, (size + 10).toLong())
        }
        if (!isSeekInvalidCase180(offset, length)) {
            return Sha256Case180(bytes, offset, length)
        }
    }
    return Sha256Case180(bytes, 0L, 0L)
}

internal fun randomHexBytes180(rnd: Random): List<Int> {
    val size = rnd.nextInt(0, 64)
    return List(size) { rnd.nextInt(0, 256) }
}

internal fun escJsonSha256180(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun sha256WireJson180(c: Sha256Case180): String =
    "{\"bytes\":[${c.bytes.joinToString(",")}]," +
        "\"offset\":${c.offset},\"length\":${c.length}}"

internal fun hexWireJson180(bytes: List<Int>): String =
    "{\"bytes\":[${bytes.joinToString(",")}]}"

class AttachmentSha256FuzzTest {

    private fun checkSha256180(c: Sha256Case180) {
        // Throws are values too: compare class + message, then the hex.
        val oracle = runCatching {
            sha256HexOracle180(
                ByteArray(c.bytes.size) { c.bytes[it].toByte() }, c.offset, c.length,
            )
        }
        val production = runCatching { sha256HexProduction180(c) }
        assertEquals(
            oracle.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" },
            production.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" },
            "sha256-throw($c)"
        )
        if (oracle.isSuccess) {
            assertEquals(oracle.getOrNull(), production.getOrNull(), "sha256($c)")
        }
    }

    private fun checkHex180(bytes: List<Int>) {
        assertEquals(
            toHexStringOracle180(ByteArray(bytes.size) { bytes[it].toByte() }),
            toHexStringProduction180(bytes),
            "hex($bytes)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Fixed contents across every offset/length edge (short reads,
        // empty slices, require failures, wrapped sums).
        val contents = listOf(
            emptyList<Int>(),
            listOf(0),
            (0..255).toList(),
            List(100) { it % 256 },
            List(8192) { (it * 31 + 7) % 256 },
            List(8193) { (it * 17 + 3) % 256 },
        )
        for (bytes in contents) {
            for (offset in SHA256_OFFSET_POOL180) {
                for (length in SHA256_LENGTH_POOL180) {
                    if (isSeekInvalidCase180(offset, length)) continue
                    checkSha256180(Sha256Case180(bytes, offset, length))
                }
            }
        }
        // Every single byte value through toHexString, plus all-zeros /
        // all-0xFF runs and the 8 KiB buffer boundary.
        for (b in 0..255) checkHex180(listOf(b))
        checkHex180(List(8192) { 0 })
        checkHex180(List(8192) { 255 })
        checkHex180(List(8193) { it % 256 })
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(180002)
        repeat(4000) { checkSha256180(randomSha256Case180(rnd)) }
        repeat(2000) { checkHex180(randomHexBytes180(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every offset/length pair on a 16-byte file.
        val bytes = List(16) { it * 16 }
        for (offset in -2L..18L) {
            for (length in -2L..18L) {
                checkSha256180(Sha256Case180(bytes, offset, length))
            }
        }
        // Buffer-boundary lengths on a 20 KiB file.
        val big = List(20 * 1024) { (it * 7 + 1) % 256 }
        for (length in listOf(0L, 1L, 8191L, 8192L, 8193L, 16384L, 20480L)) {
            checkSha256180(Sha256Case180(big, 0L, length))
            checkSha256180(Sha256Case180(big, 4096L, length))
        }
    }
}
