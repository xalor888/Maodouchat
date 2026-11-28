// MessageSafetyScanner.scan (143rd block). Oracle reimplements the
// entry point from production text: URL regex scan, keyword rules,
// card patterns, finding cap. The java.net.URI host extraction and
// the java.util.regex Patterns are the same JDK primitives production
// uses (the differential driver reimplements both independently in
// Python); the keyword lists are transcribed verbatim.
package com.maodouchat.domain.messaging

import java.util.Locale
import java.util.regex.Pattern
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- safety-scan oracles ----------

private val SCAN_URL_PATTERN: Pattern = Pattern.compile(
    "(?i)\\b((?:https?://|www\\.)[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|[\\w.-]+\\.(?:com|cn|net|org|io|co|app|me|cc|top|xyz|info|shop|vip|club|link|click|page|site|tk|gq|ml|cf)(?:/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?)"
)
private val SCAN_BANK_CARD_PATTERN: Pattern = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
private val SCAN_ID_CARD_PATTERN: Pattern = Pattern.compile("(?i)(?<!\\d)\\d{17}[\\dXx](?!\\d)")
private val SCAN_IP_HOST_PATTERN: Pattern = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

private val SCAN_PAYMENT_KEYWORDS = listOf(
    "转账", "打款", "付款", "汇款", "支付", "红包", "扫码付", "收款码", "支付宝", "微信支付",
    "银行卡", "卡号", "验证码转账", "数字人民币", "usdt", "虚拟币",
    "pay now", "wire transfer", "bank transfer", "payment", "paypal", "gift card", "crypto wallet"
)
private val SCAN_TRANSFER_KEYWORDS = listOf(
    "立刻", "马上", "尽快", "紧急", "今天必须", "先转", "先付", "定金", "保证金", "解冻", "退款手续费",
    "urgent", "immediately", "right now", "deposit", "unfreeze", "fee first"
)
private val SCAN_URGENCY_KEYWORDS = listOf(
    "马上", "立刻", "尽快", "限时", "过期", "冻结", "封号", "违法", "警方", "法院",
    "urgent", "immediately", "suspend", "locked", "police", "court"
)
private val SCAN_IMPERSONATION_KEYWORDS = listOf(
    "客服", "官方", "银行工作人员", "公检法", "公安", "法官", "快递理赔", "平台客服", "招商银行客服", "客服经理",
    "customer service", "official support", "bank staff", "administrator", "support agent"
)
private val SCAN_CREDENTIAL_KEYWORDS = listOf(
    "验证码", "密码", "支付密码", "短信码", "动态码", "seed phrase", "mnemonic", "private key",
    "password", "otp", "verification code", "pin code"
)
private val SCAN_CREDENTIAL_REQUEST_VERBS = listOf(
    "发给我", "告诉我", "提供", "发送", "回复", "报一下", "报给我", "发一下", "给我",
    "send me", "tell me", "provide", "share your", "reply with", "give me"
)
private val SCAN_SUSPICIOUS_HOST_KEYWORDS = listOf(
    "free-gift", "claim-prize", "secure-login", "account-verify", "wallet-connect", "airdrop", "login-secure"
)
private val SCAN_BRAND_TOKENS = listOf(
    "paypal", "alibaba", "taobao", "wechat", "apple", "microsoft", "amazon", "google",
    "binance", "telegram", "whatsapp", "jd", "meituan"
)
private val SCAN_TRUSTED_HOSTS = setOf(
    "maodouchat.com",
    "chat.mdou.me",
    "github.com",
    "google.com",
    "youtube.com",
    "bilibili.com",
    "zhihu.com",
    "wikipedia.org",
    "microsoft.com",
    "apple.com"
)

private fun extractHostOracle(url: String): String? {
    val normalized = when {
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
        else -> "https://$url"
    }
    return runCatching {
        val host = java.net.URI(normalized).host ?: return null
        host.trim('.').lowercase(Locale.ROOT)
    }.getOrNull()
}

private fun isSuspiciousHostOracle(host: String): Boolean {
    if (host in SCAN_TRUSTED_HOSTS) return false
    if (host.count { it == '.' } == 0) return true
    if (SCAN_IP_HOST_PATTERN.matcher(host).matches()) return true
    if (host.endsWith(".zip") || host.endsWith(".mov") || host.endsWith(".tk") || host.endsWith(".gq") || host.endsWith(".ml") || host.endsWith(".cf")) return true
    if (host.contains("xn--")) return true
    if (SCAN_SUSPICIOUS_HOST_KEYWORDS.any { host.contains(it) }) return true
    if (SCAN_BRAND_TOKENS.any { brand ->
            host.contains(brand) && !host.endsWith(".$brand.com") && host != brand && !host.endsWith(".$brand.cn")
        }) return true
    return false
}

private fun looksLikeShortLinkOracle(host: String): Boolean {
    val labels = host.split('.')
    if (labels.size < 2) return false
    val sld = labels[labels.lastIndex - 1]
    return sld.length <= 3 && host !in SCAN_TRUSTED_HOSTS && labels.last() in setOf("ly", "to", "link", "click", "page", "site")
}

private fun looksLikeHomographOracle(host: String): Boolean =
    host.any { it.code > 127 }

private fun scanOracle(rawText: String, maxFindings: Int = 7): List<Finding> {
    val text = rawText.trim()
    if (text.isBlank()) return emptyList()
    val findings = linkedMapOf<String, Finding>()
    val lower = text.lowercase(Locale.ROOT)
    val urlMatcher = SCAN_URL_PATTERN.matcher(text)
    while (urlMatcher.find()) {
        val url = urlMatcher.group() ?: continue
        val host = extractHostOracle(url) ?: continue
        if (isSuspiciousHostOracle(host) || looksLikeShortLinkOracle(host) || looksLikeHomographOracle(host)) {
            findings.putIfAbsent(
                MessageSafetyScanner.CODE_SUSPICIOUS_LINK,
                Finding(MessageSafetyScanner.CODE_SUSPICIOUS_LINK, host.take(80))
            )
        }
    }
    if (SCAN_PAYMENT_KEYWORDS.any { it in lower } && (SCAN_TRANSFER_KEYWORDS.any { it in lower } || SCAN_URGENCY_KEYWORDS.any { it in lower })) {
        findings.putIfAbsent(MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT, Finding(MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT))
    } else if (SCAN_PAYMENT_KEYWORDS.any { it in lower } && SCAN_URL_PATTERN.matcher(text).find()) {
        findings.putIfAbsent(MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT, Finding(MessageSafetyScanner.CODE_PAYMENT_INDUCEMENT))
    }
    if (SCAN_IMPERSONATION_KEYWORDS.any { it in lower } && (SCAN_URGENCY_KEYWORDS.any { it in lower } || SCAN_PAYMENT_KEYWORDS.any { it in lower })) {
        findings.putIfAbsent(MessageSafetyScanner.CODE_IMPERSONATION, Finding(MessageSafetyScanner.CODE_IMPERSONATION))
    }
    if (
        SCAN_CREDENTIAL_KEYWORDS.any { it in lower } &&
        SCAN_CREDENTIAL_REQUEST_VERBS.any { it in lower }
    ) {
        findings.putIfAbsent(MessageSafetyScanner.CODE_CREDENTIAL_REQUEST, Finding(MessageSafetyScanner.CODE_CREDENTIAL_REQUEST))
    }
    if (SCAN_BANK_CARD_PATTERN.matcher(text).find() || SCAN_ID_CARD_PATTERN.matcher(text).find()) {
        findings.putIfAbsent(MessageSafetyScanner.CODE_SENSITIVE_DATA, Finding(MessageSafetyScanner.CODE_SENSITIVE_DATA))
    }
    return findings.values.take(maxFindings.coerceIn(1, 10))
}

// ---------- generators (mirrored by Oracle143 GEN, seed 143002) ----------

private val SCAN_TEXT_POOL = arrayOf(
    "",
    "   ",
    "hello world",
    "请打开 http://secure-login.tk/verify 处理",
    "请立刻转账到我的支付宝，今天必须完成",
    "官方客服提醒：你的账户将被冻结，请立即处理",
    "官方客服：请问有什么可以帮您",
    "请把短信验证码发给我",
    "验证码一般是 6 位数字",
    "我的银行卡号是 6222021234567890123，请不要告诉别人",
    "身份证号 110105199001011234",
    "用支付宝在 https://pay.example.com/order 完成支付",
    "支付宝用起来真方便",
    "看看这个 https://github.com/xalor888",
    "点击 http://bit.ly/abc123 查看详情",
    "点击 https://t.ly/x 查看详情",
    "打开 http://192.168.1.100/login 看看",
    "请登录 https://paypal-secure.example.com/signin",
    "看看 https://www.google.com/x 的内容",
    "官方客服紧急：立刻转账并发送验证码，卡号 6222021234567890123 http://claim-prize.gq/x",
    "https://chat.mdou.me/a.apk",
    "www.example.com/x",
    "example.com",
    "https://xn--nxasmq6b.example/x",
    "https://free-gift.example.com/claim",
    "http://a.b",
    "https://bit.ly/x",
    "mailto:alice@example.com",
    "pay now! wire transfer urgent",
    "customer service: send me your password",
    "seed phrase: give me your private key",
    "6222 0212 3456 7890 123",
    "6222021234567890",
    "6222021234567890123456",
    "11010519900101123X",
    "11010519900101123x",
    "https://sub.paypal.com.evil.com/x",
    "https://paypal.com/x",
    "https://www.paypal.cn/x",
    "https://apple.com/x",
    "https://microsoft.com/x",
    "http://192.168.1.1/",
    "http://999.999.999.999/x",
    "http://1.2.3.4.5/x",
    "https://a.zip/x",
    "https://a.mov/x",
    "https://example.cf/x",
    "https://example.ml/x",
    "https://example.gq/x",
    "https://example.tk/x",
    "https://é.example.com/x",
    "https://münchen.de/x",
    "deposit now, unfreeze your account",
    "urgent: police court locked suspend",
    "tell me your otp verification code",
)
private val SCAN_URL_POOL = arrayOf(
    "http://secure-login.tk/verify", "https://bit.ly/abc123", "https://t.ly/x",
    "https://github.com/xalor888", "https://chat.mdou.me/a.apk",
    "http://192.168.1.100/login", "https://paypal-secure.example.com/signin",
    "https://www.google.com/x", "http://claim-prize.gq/x", "www.example.com/x",
    "example.com", "https://xn--nxasmq6b.example/x", "https://a.zip/x",
    "https://é.example.com/x", "http://a.b", "https://sub.paypal.com.evil.com/x",
)
private val SCAN_WORD_POOL = arrayOf(
    "转账", "支付宝", "立刻", "紧急", "客服", "官方", "验证码", "发给我", "密码",
    "pay now", "urgent", "password", "send me", "customer service",
    "hello", "world", "http://", "https://", "www.", ".com", "6222021234567890123",
)
private val SCAN_ALPHABET = "ab01 .:/_-%"

private fun randomScanText143(rnd: Random, maxLen: Int = 24): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, maxLen + 1)
    repeat(len) {
        when (rnd.nextInt(6)) {
            0 -> sb.append(('a' + rnd.nextInt(26)))
            1 -> sb.append(('0' + rnd.nextInt(10)))
            2 -> sb.append(SCAN_ALPHABET[rnd.nextInt(SCAN_ALPHABET.length)])
            3 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            4 -> sb.appendCodePoint(rnd.nextInt(0x10000, 0x1FFFF))
            else -> sb.append(SCAN_WORD_POOL[rnd.nextInt(SCAN_WORD_POOL.size)])
        }
    }
    return sb.toString()
}

private fun randomScanInput(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> SCAN_TEXT_POOL[rnd.nextInt(SCAN_TEXT_POOL.size)]
    1 -> SCAN_URL_POOL[rnd.nextInt(SCAN_URL_POOL.size)]
    2 -> randomScanText143(rnd, 24)
    else -> {
        val a = randomScanText143(rnd, 12)
        val b = SCAN_URL_POOL[rnd.nextInt(SCAN_URL_POOL.size)]
        val c = randomScanText143(rnd, 12)
        "$a $b $c"
    }
}

private val MAX_FINDINGS_POOL = intArrayOf(-1, 0, 1, 2, 3, 7, 10, 11, 100, Int.MAX_VALUE, Int.MIN_VALUE)

private fun randomMaxFindings(rnd: Random): Int =
    if (rnd.nextInt(3) == 0) MAX_FINDINGS_POOL[rnd.nextInt(MAX_FINDINGS_POOL.size)]
    else rnd.nextInt(-2, 13)

class MessageSafetyScannerFuzzTest {

    @Test
    fun pinnedBoundaries() {
        for (text in SCAN_TEXT_POOL) {
            for (max in listOf(0, 1, 2, 7, 10, 11)) {
                assertEquals(scanOracle(text, max), MessageSafetyScanner.scan(text, max), "scan text=$text max=$max")
            }
        }
        // Cap edge: one input carrying all five findings, max 0..11.
        val all = "官方客服紧急：立刻转账并发送验证码，卡号 6222021234567890123 http://claim-prize.gq/x"
        for (max in -1..12) {
            assertEquals(scanOracle(all, max), MessageSafetyScanner.scan(all, max), "scan all max=$max")
        }
        assertEquals(scanOracle(""), MessageSafetyScanner.scan(""))
        assertEquals(scanOracle("   "), MessageSafetyScanner.scan("   "))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(143002)
        repeat(1500) { i ->
            val input = randomScanInput(rnd)
            val max = randomMaxFindings(rnd)
            assertEquals(scanOracle(input, max), MessageSafetyScanner.scan(input, max), "scan iter=$i input=$input max=$max")
        }
    }

    @Test
    fun sweepExhaustive() {
        fun editsOf(target: String): List<String> {
            val out = mutableListOf<String>()
            val edits = " _Xa019./:\u0000"
            for (pos in target.indices) {
                for (rep in edits) out.add(target.substring(0, pos) + rep + target.substring(pos + 1))
                out.add(target.substring(0, pos) + target.substring(pos + 1))
                val ch = target[pos]
                out.add(target.substring(0, pos) + (if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()) + target.substring(pos + 1))
            }
            for (pos in 0..target.length) {
                for (ins in listOf("_", "X", "1", ".")) out.add(target.substring(0, pos) + ins + target.substring(pos))
            }
            return out
        }
        for (base in listOf(
            "请打开 http://secure-login.tk/verify 处理",
            "点击 http://bit.ly/abc123 查看详情",
            "我的银行卡号是 6222021234567890123，请不要告诉别人",
            "身份证号 110105199001011234",
            "请把短信验证码发给我",
            "https://www.google.com/x",
        )) {
            for (input in editsOf(base)) {
                assertEquals(scanOracle(input), MessageSafetyScanner.scan(input), "scan sweep $input")
            }
        }
        for (code in 0x00..0x7F) {
            val input = String(Character.toChars(code))
            assertEquals(scanOracle(input), MessageSafetyScanner.scan(input), "single-unit scan code=$code")
        }
    }
}
