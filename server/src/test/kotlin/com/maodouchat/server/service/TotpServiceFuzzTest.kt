package com.maodouchat.server.service

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr195b(s: String): String {
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

private fun decStr195b(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt195b(s: String?): String = if (s == null) "NULL" else "S:" + encStr195b(s)

// java.net.URLEncoder.encode(s, UTF-8) 的独立重实现：字母数字与 . - * _ 原样，
// 空格变 +，其余字节按 UTF-8 写 %XX 大写 hex。
private fun urlEncodeOracle195b(s: String): String {
    val out = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> out.append(c.toChar())
            c == ' '.code -> out.append('+')
            else -> out.append('%').append("%02X".format(c))
        }
    }
    return out.toString()
}

// provisioningUri 的独立重实现（DIGITS=6、PERIOD_SEC=30 与生产常量一致）。
private fun provisioningUriOracle195b(secretBase32: String, accountEmail: String, issuer: String): String {
    val email = accountEmail.trim()
    val label = urlEncodeOracle195b("$issuer:$email")
    val iss = urlEncodeOracle195b(issuer)
    return "otpauth://totp/$label?secret=${secretBase32.trim()}&issuer=$iss&algorithm=SHA1&digits=6&period=30"
}

private val BASE32_ALPHABET195b = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun base32EncodeOracle195b(data: ByteArray): String {
    var buffer = 0
    var bitsLeft = 0
    val out = StringBuilder()
    for (b in data) {
        buffer = (buffer shl 8) or (b.toInt() and 0xff)
        bitsLeft += 8
        while (bitsLeft >= 5) {
            val idx = (buffer shr (bitsLeft - 5)) and 0x1f
            bitsLeft -= 5
            out.append(BASE32_ALPHABET195b[idx])
        }
    }
    if (bitsLeft > 0) {
        val idx = (buffer shl (5 - bitsLeft)) and 0x1f
        out.append(BASE32_ALPHABET195b[idx])
    }
    return out.toString()
}

private fun base32DecodeOracle195b(input: String): ByteArray? {
    val cleaned = input.trim().uppercase().replace("=", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    var buffer = 0
    var bitsLeft = 0
    val out = ArrayList<Byte>()
    for (ch in cleaned) {
        val valIdx = BASE32_ALPHABET195b.indexOf(ch)
        if (valIdx < 0) return null
        buffer = (buffer shl 5) or valIdx
        bitsLeft += 5
        if (bitsLeft >= 8) {
            out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
            bitsLeft -= 8
        }
    }
    return out.toByteArray()
}

private fun generateCodeOracle195b(secret: ByteArray, counter: Long): String {
    val data = ByteBuffer.allocate(8).putLong(counter).array()
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secret, "HmacSHA1"))
    val hash = mac.doFinal(data)
    val offset = hash.last().toInt() and 0x0f
    val binary =
        ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
    val otp = binary % 1000000
    return otp.toString().padStart(6, '0')
}

private fun constantTimeEqualsOracle195b(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var r = 0
    for (i in a.indices) r = r or (a[i].code xor b[i].code)
    return r == 0
}

// verify 的独立重实现：只走 trackReplay=false、无重放守卫的确定性分支
//（生产默认回调恒接受，该分支不碰全局重放表）。
private fun verifyOracle195b(secretBase32: String, code: String, nowMs: Long, window: Int): Boolean {
    val normalized = code.trim().filter { it.isDigit() }
    if (normalized.length != 6) return false
    val secret = base32DecodeOracle195b(secretBase32) ?: return false
    val counter = nowMs / 1000L / 30L
    for (w in -window..window) {
        if (constantTimeEqualsOracle195b(generateCodeOracle195b(secret, counter + w), normalized)) return true
    }
    return false
}

private fun provisioningUriProd195b(secretBase32: String, accountEmail: String, issuer: String): String =
    TotpService.provisioningUri(secretBase32, accountEmail, issuer)

private fun verifyProd195b(secretBase32: String, code: String, nowMs: Long, window: Int): Boolean =
    TotpService.verify(secretBase32, code, nowMs, window, trackReplay = false)

private fun verifyProdSafe195b(secretBase32: String, code: String, nowMs: Long, window: Int): String {
    return try {
        verifyProd195b(secretBase32, code, nowMs, window).toString()
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

// 空 secret 解出的空字节数组会让 SecretKeySpec 抛 IllegalArgumentException，
// 与生产一致；eval 腿三方都报 THROW，生成器里不拿它造正确码。
private fun verifyOracleSafe195b(secretBase32: String, code: String, nowMs: Long, window: Int): String {
    return try {
        verifyOracle195b(secretBase32, code, nowMs, window).toString()
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

// op: uri | verify；verify 用 n1=nowMs、n2=window。
private data class TotpCase195b(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val s3: String = "",
    val n1: Long = 0,
    val n2: Int = 0,
)

private fun totpOracle195b(c: TotpCase195b): String = when (c.op) {
    "uri" -> "S:" + encStr195b(provisioningUriOracle195b(c.s1, c.s2, c.s3))
    "verify" -> verifyOracleSafe195b(c.s1, c.s2, c.n1, c.n2)
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun totpProd195b(c: TotpCase195b): String = when (c.op) {
    "uri" -> "S:" + encStr195b(provisioningUriProd195b(c.s1, c.s2, c.s3))
    "verify" -> verifyProdSafe195b(c.s1, c.s2, c.n1, c.n2)
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val TOTP_SECRETS195b = listOf(
    "", " ", "JBSWY3DPEHPK3PXP", "jbswy3dpEHPK3PXP", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
    "MY======", "MZXW6===", "ONSWG4TFOQ======", "AAAAAAAA", "22222222", "A", "AB",
    "1", "0", "8", "9", "!", "JBSW Y3DP", "JBSWY3DPEHPK3PXP ", " JBSWY3DPEHPK3PXP",
    "jbswy3dpehpk3pxp", "ZZZZZZZZ", "77777777", "NBSWY3DPEHPK3PXP", "JBSWY3DPEHPK3PXQ"
)

private val TOTP_CODES195b = listOf(
    "000000", "123456", "287082", "287083", "999999", "", " ", "12345", "1234567",
    "12 34 56", "123 456", "\uff11\uff12\uff13\uff14\uff15\uff16", "\u0661\u0662\u0663\u0664\u0665\u0666",
    "\u00b2\u00b2\u00b2\u00b2\u00b2\u00b2", "12a456", "123456\n", " 123456 ", "+12345", "-12345",
    "0000000", "abcdef", "12.3456", "123456\u0000"
)

private val TOTP_NOWS195b = listOf(
    0L, -1L, -1000L, -30000L, -30001L, 29999L, 30000L, 30001L, 59000L, 60000L,
    59999L, 60001L, 1750000000000L, Long.MAX_VALUE, Long.MIN_VALUE,
    Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 1234567890123L, -1234567890123L
)

// window 只取小值：-window 在 Int.MIN_VALUE 处溢出、超大 window 会让 HMAC 循环失控，
// 均不在差分范围内。
private val TOTP_WINDOWS195b = listOf(-2, -1, 0, 1, 2, 5)

private val TOTP_EMAILS195b = listOf(
    "", " ", "a@b.com", "user@example.com", "a+b@c.d", "a b@c", "\u00e9@\u00fc.com",
    "a@b", "a", "@", "a@b@c", "x".repeat(40), "a%40b@c", "a?b@c", "a#b@c",
    "a&b@c", "a=b@c", "a:b@c", "a/b@c", "a\\b@c", "a\"b@c", "a'b@c", "a<b@c",
    " a@b.com ", "\ta@b.com\n", "\ud83d\ude00@x.com", "a@b.com\u0000"
)

private val TOTP_ISSUERS195b = listOf(
    "\u6bdb\u8c46\u804a\u5929", "Maodou", "", " ", "A B", "a:b", "a/b", "a?b",
    "a&b", "a=b", "\u00e9\ud83d\ude00", "x".repeat(30), "*", "~", "a~b", "a*b_c-d.e"
)

private val TOTP_SECRET_CHARS195b = listOf(
    "A", "B", "C", "X", "Y", "Z", "2", "3", "4", "5", "6", "7",
    "a", "b", "c", "x", "y", "z", "0", "1", "8", "9", "=", " ", "!", "-", "_"
)

private val TOTP_CODE_CHARS195b = listOf(
    "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    "a", "b", " ", "-", "+", "\n", "\t", "\u00e9", "\uff11", "\u0661", "\u00b2"
)

private fun randomTotpSecret195b(r: Random): String {
    return when (r.nextInt(5)) {
        0 -> TOTP_SECRETS195b[r.nextInt(TOTP_SECRETS195b.size)]
        1 -> {
            val bytes = ByteArray(r.nextInt(1, 21)) { r.nextInt(256).toByte() }
            base32EncodeOracle195b(bytes)
        }
        else -> (0 until r.nextInt(25)).joinToString("") { TOTP_SECRET_CHARS195b[r.nextInt(TOTP_SECRET_CHARS195b.size)] }
    }
}

private fun randomTotpCode195b(r: Random, secret: String, nowMs: Long, window: Int): String {
    return when (r.nextInt(3)) {
        0 -> {
            // 取窗口内某 counter 的正确码，偶尔改一位数字。解出空字节数组的
            // secret（如 "A"）造不出正确码（SecretKeySpec 拒空 key），走池子。
            val sec = base32DecodeOracle195b(secret)
            if (sec == null || sec.isEmpty()) {
                TOTP_CODES195b[r.nextInt(TOTP_CODES195b.size)]
            } else {
                val counter = nowMs / 1000L / 30L
                val w = if (window >= 0) r.nextInt(-window, window + 1) else 0
                val good = generateCodeOracle195b(sec, counter + w)
                if (r.nextInt(4) == 0) {
                    val pos = r.nextInt(6)
                    good.mapIndexed { i, ch -> if (i == pos) ('0' + r.nextInt(10)) else ch }.joinToString("")
                } else {
                    good
                }
            }
        }
        1 -> TOTP_CODES195b[r.nextInt(TOTP_CODES195b.size)]
        else -> (0 until r.nextInt(9)).joinToString("") { TOTP_CODE_CHARS195b[r.nextInt(TOTP_CODE_CHARS195b.size)] }
    }
}

private fun randomTotpCase195b(r: Random): TotpCase195b {
    return if (r.nextInt(5) < 2) {
        val secret = randomTotpSecret195b(r)
        val email = if (r.nextInt(3) == 0) TOTP_EMAILS195b[r.nextInt(TOTP_EMAILS195b.size)]
            else (0 until r.nextInt(16)).joinToString("") { TOTP_CODE_CHARS195b[r.nextInt(TOTP_CODE_CHARS195b.size)] }
        val issuer = if (r.nextInt(3) == 0) TOTP_ISSUERS195b[r.nextInt(TOTP_ISSUERS195b.size)]
            else randomTotpSecret195b(r)
        TotpCase195b("uri", secret, email, issuer)
    } else {
        val secret = randomTotpSecret195b(r)
        val nowMs = if (r.nextInt(3) == 0) TOTP_NOWS195b[r.nextInt(TOTP_NOWS195b.size)]
            else r.nextLong(-100000, 2000000000000)
        val window = TOTP_WINDOWS195b[r.nextInt(TOTP_WINDOWS195b.size)]
        TotpCase195b("verify", secret, randomTotpCode195b(r, secret, nowMs, window), "", nowMs, window)
    }
}

private fun boundaryTotpCase195b(r: Random): TotpCase195b {
    return if (r.nextInt(2) == 0) {
        TotpCase195b(
            "uri",
            TOTP_SECRETS195b[r.nextInt(TOTP_SECRETS195b.size)],
            TOTP_EMAILS195b[r.nextInt(TOTP_EMAILS195b.size)],
            TOTP_ISSUERS195b[r.nextInt(TOTP_ISSUERS195b.size)]
        )
    } else {
        val secret = TOTP_SECRETS195b[r.nextInt(TOTP_SECRETS195b.size)]
        val nowMs = TOTP_NOWS195b[r.nextInt(TOTP_NOWS195b.size)]
        val window = TOTP_WINDOWS195b[r.nextInt(TOTP_WINDOWS195b.size)]
        TotpCase195b("verify", secret, TOTP_CODES195b[r.nextInt(TOTP_CODES195b.size)], "", nowMs, window)
    }
}

// ---------- tests ----------

class TotpServiceFuzzTest {

    @Test
    fun totpDifferential195() {
        val r = Random(195003)
        repeat(300) {
            val c = randomTotpCase195b(r)
            assertEquals(totpOracle195b(c), totpProd195b(c))
        }
    }

    @Test
    fun totpBoundaryDifferential195() {
        val r = Random(195004)
        repeat(300) {
            val c = boundaryTotpCase195b(r)
            assertEquals(totpOracle195b(c), totpProd195b(c))
        }
    }

    @Test
    fun totpPinned195() {
        // otpauth URI：中文 issuer 与 @ 按 UTF-8 写 %XX 大写 hex，空格变 +。
        assertEquals(
            "otpauth://totp/%E6%AF%9B%E8%B1%86%E8%81%8A%E5%A4%A9%3Aa%40b.com?secret=JBSWY3DPEHPK3PXP&issuer=%E6%AF%9B%E8%B1%86%E8%81%8A%E5%A4%A9&algorithm=SHA1&digits=6&period=30",
            provisioningUriProd195b("JBSWY3DPEHPK3PXP", "a@b.com", "\u6bdb\u8c46\u804a\u5929")
        )
        assertEquals(
            "otpauth://totp/Issuer+Name%3Aa%40b.com?secret=secret&issuer=Issuer+Name&algorithm=SHA1&digits=6&period=30",
            provisioningUriProd195b(" secret ", " a@b.com ", "Issuer Name")
        )
        assertEquals(
            provisioningUriOracle195b("JBSWY3DPEHPK3PXP", "a@b.com", "\u6bdb\u8c46\u804a\u5929"),
            provisioningUriProd195b("JBSWY3DPEHPK3PXP", "a@b.com", "\u6bdb\u8c46\u804a\u5929")
        )
        // RFC 6238 附录 B 的 SHA-1 向量：secret 为 "12345678901234567890" 的 base32，
        // T=59 秒时 8 位码 94287082，取 6 位是 287082。
        assertEquals(true, verifyProd195b("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", 59000L, 0))
        assertEquals(false, verifyProd195b("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287083", 59000L, 0))
        assertEquals(true, verifyOracle195b("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", 59000L, 0))
        // 窗口 -1 时循环为空，任何码都不接受；前后空格只影响 trim，不影响数字过滤。
        assertEquals(false, verifyProd195b("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", 59000L, -1))
        assertEquals(true, verifyProd195b("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", " 287082 ", 59000L, 0))
        // 空 secret 解不出来；码长不对直接拒绝；全角数字算数字但比不过 ASCII 期望码。
        assertEquals(false, verifyProd195b("", "123456", 0L, 1))
        assertEquals(false, verifyProd195b("JBSWY3DPEHPK3PXP", "12345", 0L, 1))
        assertEquals(false, verifyProd195b("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "\uff11\uff12\uff13\uff14\uff15\uff16", 59000L, 0))
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            TotpCase195b("uri", "JBSWY3DPEHPK3PXP", "a@b.com", "\u6bdb\u8c46\u804a\u5929"),
            TotpCase195b("uri", " secret ", " a@b.com ", "Issuer Name"),
            TotpCase195b("verify", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", "", 59000L, 0),
            TotpCase195b("verify", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", "", 59000L, -1),
            TotpCase195b("verify", "JBSWY3DPEHPK3PXP", "123456", "", 0L, 1),
        )) assertEquals(totpOracle195b(c), totpProd195b(c))
    }
}
