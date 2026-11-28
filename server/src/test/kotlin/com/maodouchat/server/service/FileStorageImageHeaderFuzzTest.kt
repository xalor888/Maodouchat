package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// 魔数字节判定逐字重写：JPG/PNG/GIF/WebP 四种头，长度不够一律 false。
private fun imageHeaderOracle198b(bytes: ByteArray): Boolean {
    val jpg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    val gif = bytes.size >= 6 && (bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" || bytes.copyOfRange(0, 6).decodeToString() == "GIF89a")
    val webp = bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    return jpg || png || gif || webp
}

private fun imageHeaderProd198b(bytes: ByteArray): Boolean =
    FileStorageService.hasSupportedImageHeader(bytes)

// op: hdr。s1=hex 编码的字节串。
private data class ImageHeaderCase198b(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
    val n3: Int = 0,
)

private fun hexToBytes198b(h: String): ByteArray {
    if (h.isEmpty()) return ByteArray(0)
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun bytesToHex198b(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

private fun imageHeaderOracle198b(c: ImageHeaderCase198b): Boolean = when (c.op) {
    "hdr" -> imageHeaderOracle198b(hexToBytes198b(c.s1))
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun imageHeaderProd198b(c: ImageHeaderCase198b): Boolean = when (c.op) {
    "hdr" -> imageHeaderProd198b(hexToBytes198b(c.s1))
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val HEADER_TEMPLATES198b = listOf(
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46),
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D),
    "GIF87a".toByteArray(Charsets.UTF_8) + byteArrayOf(1, 0, 1, 0),
    "GIF89a".toByteArray(Charsets.UTF_8) + byteArrayOf(1, 0, 1, 0),
    "RIFF".toByteArray(Charsets.UTF_8) + byteArrayOf(0x24, 0, 0, 0) + "WEBP".toByteArray(Charsets.UTF_8),
    byteArrayOf(),
    byteArrayOf(0xFF.toByte()),
    byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
    "RIFF".toByteArray(Charsets.UTF_8),
    "GIF87".toByteArray(Charsets.UTF_8),
    "GIF88a".toByteArray(Charsets.UTF_8),
    "RIFX".toByteArray(Charsets.UTF_8) + byteArrayOf(0x24, 0, 0, 0) + "WEBP".toByteArray(Charsets.UTF_8),
    "RIFF".toByteArray(Charsets.UTF_8) + byteArrayOf(0x24, 0, 0, 0) + "WEBX".toByteArray(Charsets.UTF_8),
)

private fun randomHeaderBytes198b(r: Random): ByteArray {
    return when (r.nextInt(4)) {
        0 -> HEADER_TEMPLATES198b[r.nextInt(HEADER_TEMPLATES198b.size)]
        1 -> {
            val base = HEADER_TEMPLATES198b[r.nextInt(HEADER_TEMPLATES198b.size)].copyOf()
            if (base.isNotEmpty()) {
                val pos = r.nextInt(base.size)
                base[pos] = r.nextInt(256).toByte()
            }
            base
        }
        2 -> {
            val base = HEADER_TEMPLATES198b[r.nextInt(HEADER_TEMPLATES198b.size)]
            base.copyOf(r.nextInt(base.size + 1))
        }
        else -> ByteArray(r.nextInt(0, 17)) { r.nextInt(256).toByte() }
    }
}

private fun randomHeaderCase198b(r: Random): ImageHeaderCase198b =
    ImageHeaderCase198b("hdr", bytesToHex198b(randomHeaderBytes198b(r)))

private fun boundaryHeaderCase198b(r: Random): ImageHeaderCase198b =
    ImageHeaderCase198b(
        "hdr",
        bytesToHex198b(HEADER_TEMPLATES198b[r.nextInt(HEADER_TEMPLATES198b.size)]),
    )

// ---------- tests ----------

class FileStorageImageHeaderFuzzTest {

    @Test
    fun imageHeaderDifferential198() {
        val r = Random(198201)
        repeat(300) {
            val c = randomHeaderCase198b(r)
            assertEquals(imageHeaderOracle198b(c), imageHeaderProd198b(c))
        }
    }

    @Test
    fun imageHeaderBoundaryDifferential198() {
        val r = Random(198202)
        repeat(300) {
            val c = boundaryHeaderCase198b(r)
            assertEquals(imageHeaderOracle198b(c), imageHeaderProd198b(c))
        }
    }

    @Test
    fun imageHeaderPinned198() {
        // 四种真头各一例 + 截断/错字节/空串反例。
        assertEquals(true, imageHeaderProd198b(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(true, imageHeaderProd198b(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(true, imageHeaderProd198b("GIF87a".toByteArray(Charsets.UTF_8)))
        assertEquals(true, imageHeaderProd198b("GIF89a".toByteArray(Charsets.UTF_8)))
        assertEquals(true, imageHeaderProd198b("RIFF".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray(Charsets.UTF_8)))
        assertEquals(false, imageHeaderProd198b(byteArrayOf()))
        assertEquals(false, imageHeaderProd198b(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertEquals(false, imageHeaderProd198b("GIF87".toByteArray(Charsets.UTF_8)))
        assertEquals(false, imageHeaderProd198b("GIF88a".toByteArray(Charsets.UTF_8)))
        assertEquals(false, imageHeaderProd198b("RIFF".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0, 0) + "WEBX".toByteArray(Charsets.UTF_8)))
        for (c in listOf(
            ImageHeaderCase198b("hdr", bytesToHex198b(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))),
            ImageHeaderCase198b("hdr", bytesToHex198b(byteArrayOf())),
            ImageHeaderCase198b("hdr", bytesToHex198b("GIF89a".toByteArray(Charsets.UTF_8))),
            ImageHeaderCase198b("hdr", bytesToHex198b("RIFF".toByteArray(Charsets.UTF_8) + byteArrayOf(1, 2, 3, 4) + "WEBP".toByteArray(Charsets.UTF_8))),
        )) assertEquals(imageHeaderOracle198b(c), imageHeaderProd198b(c))
    }
}
