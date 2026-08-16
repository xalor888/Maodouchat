package com.maodouchat.util

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/**
 * 本机链接预览：只从消息正文抽 URL / 解析 HTML meta，不把 URL 交给第三方 OG 服务。
 */
object LinkPreviewPolicy {
    data class Preview(
        val url: String,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
        val siteName: String?,
    )

    private val URL_PATTERN: Pattern = Pattern.compile(
        "(?i)\\b((?:https?://|www\\.)[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)"
    )

    /** 取正文中第一个 http(s)/www URL，规范化 scheme。 */
    fun firstHttpUrl(text: String): String? {
        // 9.149：与 parsedContent 一致取最后一个 `<meta>`（真实 meta 块恒在末尾）——
        // 正文含字面 `<meta>` 时此前按首个出现位置截断，后半段正文里的 URL 被漏掉
        val plain = text.lastIndexOf("<meta>").let { idx ->
            if (idx < 0) text else text.substring(0, idx)
        }.trim()
        if (plain.isBlank()) return null
        val matcher = URL_PATTERN.matcher(plain)
        if (!matcher.find()) return null
        val raw = matcher.group(1)?.trim()?.trimEnd('.', ',', ')', ']', '}', '"', '\'') ?: return null
        if (raw.isBlank()) return null
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("www.", ignoreCase = true) -> "https://$raw"
            else -> return null
        }
        return sanitizeUrl(withScheme)
    }

    fun sanitizeUrl(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.trim()?.trim('.') ?: return null
            if (host.isBlank()) return null
            // 拒绝内网/非公网探测目标（防止客户端 SSRF）
            if (host.equals("localhost", true) || host.endsWith(".local", true)) return null
            // 拒绝非标准 IP 字面量编码（十进制整数 / 十六进制），这些会被 OkHttp 解析为内网地址
            // 例如 2130706433 (=127.0.0.1)、0x7f000001 (=127.0.0.1)。
            if (host.matches(Regex("""^(0x[0-9a-fA-F]+|\d+)$"""))) return null
            // Reject dotted hexadecimal shorthand such as 0x7f.0.0.1, which some parsers
            // resolve as an IPv4-mapped private address.
            if (host.matches(Regex("""^0x[0-9a-fA-F]+(\.[0-9a-fA-F]+)*$"""))) return null
            // Reject decimal IPv4 shorthand (127.1, 127.0.1). Four-label dotted decimal is
            // still allowed only when each octet is strict decimal and is not private.
            val dottedNumericLabels = host.split(".")
            if (dottedNumericLabels.size in 2..4 && dottedNumericLabels.all { it.matches(Regex("""\d+""")) }) {
                if (dottedNumericLabels.size != 4) return null
                val octets = dottedNumericLabels.map { it.toIntOrNull() }
                if (dottedNumericLabels.zip(octets).any { (raw, n) ->
                        n == null || n !in 0..255 || (n != 0 && raw != n.toString())
                    }
                ) return null
                // Keep the explicit octet null checks after validation so later indexed reads are total.
                val a = octets[0] ?: return null
                val b = octets[1] ?: return null
                if (a == 0 || a == 10 || a == 127 ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127)
                ) return null
            }
            // IPv6 literal 过滤：环回、未指定、链路本地、站点本地、组播，以及
            // IPv4-mapped/IPv4-compatible 编码都不能作为链接预览探测目标。
            if (host.contains(":")) {
                val h = host.removeSurrounding("[", "]").lowercase(Locale.ROOT)
                if (h.contains("::ffff:") || isNonPublicIpv6Literal(h)) return null
            }
            uri.toString()
        }.getOrNull()
    }

    fun displayHost(url: String): String {
        return runCatching { URI(url).host?.removePrefix("www.") ?: url }
            .getOrDefault(url)
            .take(96)
    }

    private fun isNonPublicIpv6Literal(host: String): Boolean {
        return runCatching {
            val address = InetAddress.getByName(host)
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                (address is Inet6Address && address.isIPv4CompatibleAddress)
        }.getOrDefault(true)
    }

    /**
     * 从 HTML 头截取 Open Graph / 标准 title。
     * [html] 建议调用方只传前 64KB。
     */
    fun parseHtmlPreview(url: String, html: String): Preview {
        val head = extractHead(html)
        val title = metaContent(head, property = "og:title")
            ?: metaContent(head, name = "twitter:title")
            ?: titleTag(head)
        val description = metaContent(head, property = "og:description")
            ?: metaContent(head, name = "description")
            ?: metaContent(head, name = "twitter:description")
        val image = metaContent(head, property = "og:image")
            ?: metaContent(head, name = "twitter:image")
        val site = metaContent(head, property = "og:site_name")
            ?: displayHost(url)
        return Preview(
            url = url,
            title = cleanText(title)?.take(220),
            description = cleanText(description)?.take(400),
            imageUrl = resolveUrl(url, image),
            siteName = cleanText(site)?.take(120),
        )
    }

    fun isUseful(preview: Preview): Boolean {
        return !preview.title.isNullOrBlank() ||
            !preview.description.isNullOrBlank() ||
            !preview.imageUrl.isNullOrBlank()
    }

    private fun extractHead(html: String): String {
        val lower = html.lowercase(Locale.ROOT)
        val end = lower.indexOf("</head>").let { if (it < 0) html.length.coerceAtMost(65_536) else it }
        return html.substring(0, end.coerceAtMost(html.length))
    }

    private fun metaContent(head: String, property: String? = null, name: String? = null): String? {
        val key = property ?: name ?: return null
        val attr = if (property != null) "property" else "name"
        // 宽松匹配 <meta ... content="...">
        val pattern = Pattern.compile(
            """(?is)<meta\b[^>]*\b$attr\s*=\s*["']${Pattern.quote(key)}["'][^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*/?>""" +
                """|(?is)<meta\b[^>]*\bcontent\s*=\s*["']([^"']+)["'][^>]*\b$attr\s*=\s*["']${Pattern.quote(key)}["'][^>]*/?>"""
        )
        val m = pattern.matcher(head)
        if (!m.find()) return null
        return m.group(1) ?: m.group(2)
    }

    private fun titleTag(head: String): String? {
        val m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(head)
        if (!m.find()) return null
        return m.group(1)
    }

    private fun cleanText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(Regex("\\s+"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun resolveUrl(base: String, maybeRelative: String?): String? {
        val raw = maybeRelative?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val resolved = URI(base).resolve(raw).toString()
            sanitizeUrl(resolved)
        }.getOrNull()
    }
}
