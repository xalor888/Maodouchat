package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [QrPayloadCodec] (checklist line 931, 59th
 * block). Test-only, zero production changes.
 *
 * The oracle re-implements the codec by hand: UTF-8 encoding (a lone
 * surrogate becomes one 0x3F byte, as probed) plus a Base64-URL encoder
 * without padding, and a manual trim over the 28-unit whitespace set.
 * Random inputs use Random(42), fresh per @Test.
 */
class QrPayloadCodecFuzzTest {

// ---------- C2 QrPayloadCodec oracle ----------

// Hand-written mirror of String.getBytes(UTF_8): a valid surrogate pair
// encodes as 4 bytes; a lone surrogate encodes as a single 0x3F byte
// (probed JDK behavior — not U+FFFD).
private fun utf8BytesOracle(s: String): ByteArray {
    val out = ArrayList<Byte>()
    var i = 0
    while (i < s.length) {
        val c1 = s[i]
        if (c1.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            val cp = Character.toCodePoint(c1, s[i + 1])
            out.add((0xF0 or (cp shr 18)).toByte())
            out.add((0x80 or ((cp shr 12) and 0x3F)).toByte())
            out.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
            out.add((0x80 or (cp and 0x3F)).toByte())
            i += 2
        } else if (c1.isSurrogate()) {
            out.add(0x3F.toByte())
            i += 1
        } else {
            val cp = c1.code
            when {
                cp < 0x80 -> out.add(cp.toByte())
                cp < 0x800 -> {
                    out.add((0xC0 or (cp shr 6)).toByte())
                    out.add((0x80 or (cp and 0x3F)).toByte())
                }
                else -> {
                    out.add((0xE0 or (cp shr 12)).toByte())
                    out.add((0x80 or ((cp shr 6) and 0x3F)).toByte())
                    out.add((0x80 or (cp and 0x3F)).toByte())
                }
            }
            i += 1
        }
    }
    val arr = ByteArray(out.size)
    for (j in out.indices) arr[j] = out[j]
    return arr
}

private val B64_URL_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

// Hand-written Base64 URL encoder, no padding: 3 bytes -> 4 chars, a
// 1-byte tail -> 2 chars, a 2-byte tail -> 3 chars.
private fun b64UrlOracle(bytes: ByteArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i + 3 <= bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        val b2 = bytes[i + 2].toInt() and 0xFF
        sb.append(B64_URL_ALPHABET[b0 shr 2])
        sb.append(B64_URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
        sb.append(B64_URL_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
        sb.append(B64_URL_ALPHABET[b2 and 0x3F])
        i += 3
    }
    val rem = bytes.size - i
    if (rem == 1) {
        val b0 = bytes[i].toInt() and 0xFF
        sb.append(B64_URL_ALPHABET[b0 shr 2])
        sb.append(B64_URL_ALPHABET[(b0 and 0x03) shl 4])
    } else if (rem == 2) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = bytes[i + 1].toInt() and 0xFF
        sb.append(B64_URL_ALPHABET[b0 shr 2])
        sb.append(B64_URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
        sb.append(B64_URL_ALPHABET[(b1 and 0x0F) shl 2])
    }
    return sb.toString()
}

private fun encodePartOracle(value: String): String =
    b64UrlOracle(utf8BytesOracle(value))

// Hand-written mirror of String.trim(): strip leading/trailing chars in
// the probed 28-unit Kotlin whitespace set (Char.isWhitespace).
private fun trimOracle(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].isWhitespace()) start++
    while (end > start && s[end - 1].isWhitespace()) end--
    return s.substring(start, end)
}

private fun encodeUserOracle(userId: String): String = "maodouchat:user:" + userId

private fun encodeChatOracle(chatId: String): String = "maodouchat:chat:" + chatId

private fun encodeInviteOracle(token: String): String =
    "maodouchat:chat-invite:v1:" + trimOracle(token)

private fun encodeSafety1Oracle(
    ownerUserId: String, ownerDeviceId: Int,
    peerUserId: String, peerDeviceId: Int, safetyCode: String
): String = "maodouchat:safety:" + encodePartOracle(ownerUserId) + ":" +
    ownerDeviceId + ":" + encodePartOracle(peerUserId) + ":" +
    peerDeviceId + ":" + encodePartOracle(safetyCode)

private fun encodeSafety2Oracle(
    ownerUserId: String, ownerDeviceId: Int,
    peerUserId: String, peerDeviceId: Int,
    ownerIdentityFingerprint: String, peerIdentityFingerprint: String
): String = "maodouchat:safety:v2:" + encodePartOracle(ownerUserId) + ":" +
    ownerDeviceId + ":" + encodePartOracle(peerUserId) + ":" +
    peerDeviceId + ":" + encodePartOracle(ownerIdentityFingerprint) + ":" +
    encodePartOracle(peerIdentityFingerprint)

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended (with the
// multi-code-unit uppercase expansions), ligatures, fullwidth forms,
// Deseret / Adlam / Medefaidrin / Vithkuqi astral letters, CJK, combining
// marks, digits of several scripts, and general punctuation. Osage small
// letters are excluded: JDK 17 leaves them unchanged on uppercase while
// newer Unicode data maps them (pinned separately).
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0660 to 0x0660, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E60 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F600 to 0x1F600
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> {
                // Lone surrogate (high or low): survives take/uppercase
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

private fun randomInt(rnd: Random): Int = when (rnd.nextInt(8)) {
    0 -> 0; 1 -> 1; 2 -> -1; 3 -> 42; 4 -> -42
    5 -> Int.MIN_VALUE; 6 -> Int.MAX_VALUE
    else -> rnd.nextInt()
}

    @Test
    fun userRandom() {
        val rnd = Random(42)
        repeat(300) {
            val id = randomString(rnd)
            assertEquals(encodeUserOracle(id), QrPayloadCodec.encodeUserQrPayload(id))
        }
    }

    @Test
    fun chatRandom() {
        val rnd = Random(42)
        repeat(300) {
            val id = randomString(rnd)
            assertEquals(encodeChatOracle(id), QrPayloadCodec.encodeChatQrPayload(id))
        }
    }

    @Test
    fun inviteRandom() {
        val rnd = Random(42)
        repeat(300) {
            val token = randomString(rnd)
            assertEquals(encodeInviteOracle(token), QrPayloadCodec.encodeChatInviteQrPayload(token))
        }
    }

    @Test
    fun safetyRandom() {
        val rnd = Random(42)
        repeat(150) {
            val owner = randomString(rnd)
            val ownerDevice = randomInt(rnd)
            val peer = randomString(rnd)
            val peerDevice = randomInt(rnd)
            val code = randomString(rnd)
            assertEquals(
                encodeSafety1Oracle(owner, ownerDevice, peer, peerDevice, code),
                QrPayloadCodec.encodeSafetyQrPayload(owner, ownerDevice, peer, peerDevice, code)
            )
        }
        repeat(150) {
            val owner = randomString(rnd)
            val ownerDevice = randomInt(rnd)
            val peer = randomString(rnd)
            val peerDevice = randomInt(rnd)
            val fpA = randomString(rnd)
            val fpB = randomString(rnd)
            assertEquals(
                encodeSafety2Oracle(owner, ownerDevice, peer, peerDevice, fpA, fpB),
                QrPayloadCodec.encodeSafetyQrPayload(owner, ownerDevice, peer, peerDevice, fpA, fpB)
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // user/chat payloads: plain prefix + id, no encoding.
        assertEquals("maodouchat:user:", QrPayloadCodec.encodeUserQrPayload(""))
        assertEquals("maodouchat:user:u1", QrPayloadCodec.encodeUserQrPayload("u1"))
        assertEquals("maodouchat:user:a:b", QrPayloadCodec.encodeUserQrPayload("a:b"))
        assertEquals("maodouchat:user:\ud83d\ude00", QrPayloadCodec.encodeUserQrPayload("\ud83d\ude00"))
        assertEquals("maodouchat:user:\ud800", QrPayloadCodec.encodeUserQrPayload("\ud800"))
        assertEquals("maodouchat:user:a b", QrPayloadCodec.encodeUserQrPayload("a b"))
        assertEquals("maodouchat:chat:", QrPayloadCodec.encodeChatQrPayload(""))
        assertEquals("maodouchat:chat:c1", QrPayloadCodec.encodeChatQrPayload("c1"))
        assertEquals("maodouchat:chat:x:y:z", QrPayloadCodec.encodeChatQrPayload("x:y:z"))
        assertEquals("maodouchat:chat:\ud800\udc00", QrPayloadCodec.encodeChatQrPayload("\ud800\udc00"))
        // invite: the token is trimmed (28-unit whitespace set).
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("  tok  "))
        assertEquals("maodouchat:chat-invite:v1:", QrPayloadCodec.encodeChatInviteQrPayload(""))
        assertEquals("maodouchat:chat-invite:v1:", QrPayloadCodec.encodeChatInviteQrPayload("   "))
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("\u00a0tok\u00a0"))
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("\u202ftok\u202f"))
        assertEquals("maodouchat:chat-invite:v1:\u0085tok\u0085", QrPayloadCodec.encodeChatInviteQrPayload("\u0085tok\u0085"))
        assertEquals("maodouchat:chat-invite:v1:\ufefftok\ufeff", QrPayloadCodec.encodeChatInviteQrPayload("\ufefftok\ufeff"))
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("\u0009\u000a tok \u000d\u000a"))
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("tok"))
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("\u2007tok\u2007"))
        assertEquals("maodouchat:chat-invite:v1:tok", QrPayloadCodec.encodeChatInviteQrPayload("\u3000tok\u3000"))
        // safety v1: Base64-URL parts, no padding; lone surrogates
        // encode as 0x3F, so their part is "Pw".
        assertEquals("maodouchat:safety:b3duZXI:0:cGVlcg:1:MTIzIDQ1Ng", QrPayloadCodec.encodeSafetyQrPayload("owner", 0, "peer", 1, "123 456"))
        assertEquals("maodouchat:safety::0::0:", QrPayloadCodec.encodeSafetyQrPayload("", 0, "", 0, ""))
        assertEquals("maodouchat:safety:YQ:-1:Yg:-2:Yw", QrPayloadCodec.encodeSafetyQrPayload("a", -1, "b", -2, "c"))
        assertEquals("maodouchat:safety:8J-YgA:2147483647:w58:-2147483648:Pw", QrPayloadCodec.encodeSafetyQrPayload("\ud83d\ude00", 2147483647, "\u00df", -2147483648, "\ud800"))
        assertEquals("maodouchat:safety:Zg:1:Zm8:2:Zm9v", QrPayloadCodec.encodeSafetyQrPayload("f", 1, "fo", 2, "foo"))
        assertEquals("maodouchat:safety:b3duZXI:2147483647:cGVlcg:-2147483648:eA", QrPayloadCodec.encodeSafetyQrPayload("owner", 2147483647, "peer", -2147483648, "x"))
        assertEquals("maodouchat:safety:w6k:0:5Lit:0:8J-YgA", QrPayloadCodec.encodeSafetyQrPayload("\u00e9", 0, "\u4e2d", 0, "\ud83d\ude00"))
        // safety v2: a "v2" marker segment, then two fingerprint parts.
        assertEquals("maodouchat:safety:v2:b3duZXI:0:cGVlcg:1:ZnBB:ZnBC", QrPayloadCodec.encodeSafetyQrPayload("owner", 0, "peer", 1, "fpA", "fpB"))
        assertEquals("maodouchat:safety:v2::0::0::", QrPayloadCodec.encodeSafetyQrPayload("", 0, "", 0, "", ""))
        assertEquals("maodouchat:safety:v2:YQ:-1:Yg:-2:Pw:Pw", QrPayloadCodec.encodeSafetyQrPayload("a", -1, "b", -2, "\ud800", "\udc00"))
        assertEquals("maodouchat:safety:v2:8J-YgA:42:w58:-42:76yA:x7A", QrPayloadCodec.encodeSafetyQrPayload("\ud83d\ude00", 42, "\u00df", -42, "\ufb00", "\u01f0"))
    }
}
