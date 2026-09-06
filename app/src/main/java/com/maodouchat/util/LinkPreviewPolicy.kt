package com.maodouchat.util

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/**
 * 本机链接预览策略（8.54 / U07）：只从消息正文抽 URL / 解析 HTML meta，不把 URL 交给第三方 OG 服务。
 * 严格防御客户端 SSRF，阻断内网地址、私有网段、各种 IP 编码变体及危险协议。
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

    private val ALLOWED_WEB_PORTS = setOf(80, 443, 8080, 8443)

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

    /**
     * 校验并清洗待抓取的 URL，阻断各类内网/私网探测与绕过变体（防止客户端 SSRF）。
     * 验证失败或存在安全隐患时返回 null。
     */
    fun sanitizeUrl(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null

            // 拒绝包含 userinfo 的 URL（如 http://user:pass@host/ 或 http://admin@localhost/）
            if (!uri.rawUserInfo.isNullOrEmpty()) return null

            val host = uri.host?.trim()?.trim('.') ?: return null
            if (host.isBlank()) return null

            // 拒绝包含百分号编码的 host（防止通过 %2e%2e 等方式混淆主机名）
            if (host.contains("%")) return null

            // 端口约束：若显式指定端口，仅允许标准 Web 服务端口，防止针对内网服务（如 Redis/SSH/SMTP）的端口扫描
            val port = uri.port
            if (port != -1 && port !in ALLOWED_WEB_PORTS) return null

            // 拒绝内网/非公网主机名
            if (host.equals("localhost", true) || host.endsWith(".local", true) || host.endsWith(".internal", true)) {
                return null
            }

            // 拒绝单个整数或十六进制字面量（例如 2130706433 或 0x7f000001 即 127.0.0.1）
            if (host.matches(Regex("""^(0x[0-9a-fA-F]+|\d+)$"""))) return null

            // 检查带点分段的数值/十六进制/八进制混合变体（例如 127.1, 127.0.0.0x1, 0x7f.0.0.1, 0177.0.0.1）
            val parts = host.split(".")
            val isNumericOrHexLabels = parts.all { it.matches(Regex("""(?i)^(0x[0-9a-fA-F]+|\d+)$""")) }
            if (isNumericOrHexLabels) {
                // 必须严格为 4 段十进制，不能包含 0x，不能有前导 0（八进制形式）
                if (parts.size != 4) return null
                if (parts.any { it.startsWith("0x", ignoreCase = true) }) return null

                val octets = parts.map { it.toIntOrNull() }
                if (parts.zip(octets).any { (raw, n) ->
                        n == null || n !in 0..255 || raw != n.toString()
                    }
                ) return null

                val a = octets[0] ?: return null
                val b = octets[1] ?: return null
                val c = octets[2] ?: return null

                // 0.0.0.0/8 (当前网络)
                if (a == 0) return null
                // 10.0.0.0/8 (私有 A 类)
                if (a == 10) return null
                // 127.0.0.0/8 (回环)
                if (a == 127) return null
                // 169.254.0.0/16 (链路本地 / 关键云厂商元数据 169.254.169.254)
                if (a == 169 && b == 254) return null
                // 172.16.0.0/12 (私有 B 类)
                if (a == 172 && b in 16..31) return null
                // 192.168.0.0/16 (私有 C 类)
                if (a == 192 && b == 168) return null
                // 100.64.0.0/10 (运营商级 NAT)
                if (a == 100 && b in 64..127) return null
                // 192.0.0.0/24 (IETF 协议分配)
                if (a == 192 && b == 0 && c == 0) return null
                // 192.0.2.0/24 (TEST-NET-1)
                if (a == 192 && b == 0 && c == 2) return null
                // 198.18.0.0/15 (网络基准测试)
                if (a == 198 && b in 18..19) return null
                // 198.51.100.0/24 (TEST-NET-2)
                if (a == 198 && b == 51 && c == 100) return null
                // 203.0.113.0/24 (TEST-NET-3)
                if (a == 203 && b == 0 && c == 113) return null
                // 224.0.0.0/4 (组播) 与 240.0.0.0/4 (保留)
                if (a >= 224) return null
            }

            // IPv6 literal 过滤：环回、未指定、链路本地、站点本地、组播、ULA (fc00::/7)、IPv4-mapped (::ffff:) 等
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
            if (address !is Inet6Address) return true
            if (address.isIPv4CompatibleAddress) return true
            if (address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return true
            }
            val bytes = address.address
            if (bytes.size == 16) {
                val first = bytes[0].toInt() and 0xff
                // fc00::/7 (Unique local addresses ULA: fc00::/8 与 fd00::/8)
                if (first and 0xfe == 0xfc) return true
                // 2001:db8::/32 (文档/示例专用地址)
                val second = bytes[1].toInt() and 0xff
                val third = bytes[2].toInt() and 0xff
                val fourth = bytes[3].toInt() and 0xff
                if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return true
            }
            false
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
