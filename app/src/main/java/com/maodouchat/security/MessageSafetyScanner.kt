package com.maodouchat.security

import java.util.Locale
import java.util.regex.Pattern

/**
 * 纯端侧消息安全检查：不上传内容，默认由调用方在用户开启后执行。
 */
object MessageSafetyScanner {
    data class Finding(
        val code: String,
        val matched: String? = null
    )

    fun scan(rawText: String, maxFindings: Int = 7): List<Finding> {
        val text = rawText.trim()
        if (text.isBlank()) return emptyList()
        val findings = linkedMapOf<String, Finding>()
        val lower = text.lowercase(Locale.ROOT)

        // Matcher.results() is API 34+; use find() so minSdk 26 stays supported.
        val urlMatcher = URL_PATTERN.matcher(text)
        while (urlMatcher.find()) {
            val url = urlMatcher.group() ?: continue
            val host = extractHost(url) ?: continue
            if (isSuspiciousHost(host) || looksLikeShortLink(host) || looksLikeHomograph(host)) {
                findings.putIfAbsent(
                    CODE_SUSPICIOUS_LINK,
                    Finding(CODE_SUSPICIOUS_LINK, host.take(80))
                )
            }
        }

        if (PAYMENT_KEYWORDS.any { it in lower } && (TRANSFER_KEYWORDS.any { it in lower } || URGENCY_KEYWORDS.any { it in lower })) {
            findings.putIfAbsent(CODE_PAYMENT_INDUCEMENT, Finding(CODE_PAYMENT_INDUCEMENT))
        } else if (PAYMENT_KEYWORDS.any { it in lower } && URL_PATTERN.matcher(text).find()) {
            findings.putIfAbsent(CODE_PAYMENT_INDUCEMENT, Finding(CODE_PAYMENT_INDUCEMENT))
        }

        if (IMPERSONATION_KEYWORDS.any { it in lower } && (URGENCY_KEYWORDS.any { it in lower } || PAYMENT_KEYWORDS.any { it in lower })) {
            findings.putIfAbsent(CODE_IMPERSONATION, Finding(CODE_IMPERSONATION))
        }

        if (
            CREDENTIAL_KEYWORDS.any { it in lower } &&
            CREDENTIAL_REQUEST_VERBS.any { it in lower }
        ) {
            findings.putIfAbsent(CODE_CREDENTIAL_REQUEST, Finding(CODE_CREDENTIAL_REQUEST))
        }

        if (BANK_CARD_PATTERN.matcher(text).find() || ID_CARD_PATTERN.matcher(text).find()) {
            findings.putIfAbsent(CODE_SENSITIVE_DATA, Finding(CODE_SENSITIVE_DATA))
        }

        return findings.values.take(maxFindings.coerceIn(1, 10))
    }

    private fun extractHost(url: String): String? {
        val normalized = when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            else -> "https://$url"
        }
        return runCatching {
            val host = java.net.URI(normalized).host ?: return null
            host.trim('.').lowercase(Locale.ROOT)
        }.getOrNull()
    }

    private fun isSuspiciousHost(host: String): Boolean {
        if (host in TRUSTED_HOSTS) return false
        if (host.count { it == '.' } == 0) return true
        if (IP_HOST_PATTERN.matcher(host).matches()) return true
        if (host.endsWith(".zip") || host.endsWith(".mov") || host.endsWith(".tk") || host.endsWith(".gq") || host.endsWith(".ml") || host.endsWith(".cf")) return true
        if (host.contains("xn--")) return true
        if (SUSPICIOUS_HOST_KEYWORDS.any { host.contains(it) }) return true
        // 品牌仿冒：paypal-secure.example.com
        if (BRAND_TOKENS.any { brand ->
                host.contains(brand) && !host.endsWith(".$brand.com") && host != brand && !host.endsWith(".$brand.cn")
            }) return true
        return false
    }

    private fun looksLikeShortLink(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        val sld = labels[labels.lastIndex - 1]
        return sld.length <= 3 && host !in TRUSTED_HOSTS && labels.last() in setOf("ly", "to", "link", "click", "page", "site")
    }

    private fun looksLikeHomograph(host: String): Boolean {
        // 含非 ASCII 字符的域名更易用于仿冒
        return host.any { it.code > 127 }
    }

    const val CODE_SUSPICIOUS_LINK = "suspicious_link"
    const val CODE_PAYMENT_INDUCEMENT = "payment_inducement"
    const val CODE_IMPERSONATION = "impersonation"
    const val CODE_CREDENTIAL_REQUEST = "credential_request"
    const val CODE_SENSITIVE_DATA = "sensitive_data"

    private val URL_PATTERN: Pattern = Pattern.compile(
        "(?i)\\b((?:https?://|www\\.)[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|[\\w.-]+\\.(?:com|cn|net|org|io|co|app|me|cc|top|xyz|info|shop|vip|club|link|click|page|site|tk|gq|ml|cf)(?:/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?)"
    )
    private val BANK_CARD_PATTERN: Pattern = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val ID_CARD_PATTERN: Pattern = Pattern.compile("(?i)(?<!\\d)\\d{17}[\\dXx](?!\\d)")
    private val IP_HOST_PATTERN: Pattern = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

    private val PAYMENT_KEYWORDS = listOf(
        "转账", "打款", "付款", "汇款", "支付", "红包", "扫码付", "收款码", "支付宝", "微信支付",
        "银行卡", "卡号", "验证码转账", "数字人民币", "usdt", "虚拟币",
        "pay now", "wire transfer", "bank transfer", "payment", "paypal", "gift card", "crypto wallet"
    )
    private val TRANSFER_KEYWORDS = listOf(
        "立刻", "马上", "尽快", "紧急", "今天必须", "先转", "先付", "定金", "保证金", "解冻", "退款手续费",
        "urgent", "immediately", "right now", "deposit", "unfreeze", "fee first"
    )
    private val URGENCY_KEYWORDS = listOf(
        "马上", "立刻", "尽快", "限时", "过期", "冻结", "封号", "违法", "警方", "法院",
        "urgent", "immediately", "suspend", "locked", "police", "court"
    )
    private val IMPERSONATION_KEYWORDS = listOf(
        "客服", "官方", "银行工作人员", "公检法", "公安", "法官", "快递理赔", "平台客服", "招商银行客服", "客服经理",
        "customer service", "official support", "bank staff", "administrator", "support agent"
    )
    private val CREDENTIAL_KEYWORDS = listOf(
        "验证码", "密码", "支付密码", "短信码", "动态码", "seed phrase", "mnemonic", "private key",
        "password", "otp", "verification code", "pin code"
    )
    private val CREDENTIAL_REQUEST_VERBS = listOf(
        "发给我", "告诉我", "提供", "发送", "回复", "报一下", "报给我", "发一下", "给我",
        "send me", "tell me", "provide", "share your", "reply with", "give me"
    )
    private val SUSPICIOUS_HOST_KEYWORDS = listOf(
        "free-gift", "claim-prize", "secure-login", "account-verify", "wallet-connect", "airdrop", "login-secure"
    )
    private val BRAND_TOKENS = listOf(
        "paypal", "alibaba", "taobao", "wechat", "apple", "microsoft", "amazon", "google",
        "binance", "telegram", "whatsapp", "jd", "meituan"
    )
    private val TRUSTED_HOSTS = setOf(
        "maodouchat.com",
        "github.com",
        "google.com",
        "youtube.com",
        "bilibili.com",
        "zhihu.com",
        "wikipedia.org",
        "microsoft.com",
        "apple.com"
    )
}
