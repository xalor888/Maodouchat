package com.maodouchat.util

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
        val plain = text.substringBefore("<meta>").trim()
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
            // 点分十进制 IPv4：每段必须严格 0-255 且不以 0 开头（除非本身就是 "0"），
            // 否则当作非法（拦截八进制前导零如 0177.0.0.1 ≡ 127.0.0.1）。
            if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
                val parts = host.split(".")
                val octets = parts.map { it.toIntOrNull() }
                if (parts.zip(octets).any { (raw, n) ->
                        n == null || n !in 0..255 || (n != 0 && raw != n.toString())
                    }
                ) return null
                // 8.49 防御：网络主机名解析结果逐位判空（此前依赖第 54 行正则保证 4 段，脆弱不变量）
                val a = octets[0] ?: return null
                val b = octets[1] ?: return null
                if (a == 0 || a == 10 || a == 127 ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127)
                ) return null
            }
            // IPv6 内网/环回地址（含 IPv4-mapped）
            if (host.contains(":")) {
                val h = host.removeSurrounding("[", "]").lowercase()
                if (h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
                    h.startsWith("fe8") || h.startsWith("fe9") ||
                    h.startsWith("fea") || h.startsWith("feb") ||
                    h.contains("::ffff:")
                ) return null
            }
            uri.toString()
        }.getOrNull()
    }

    fun displayHost(url: String): String {
        return runCatching { URI(url).host?.removePrefix("www.") ?: url }
            .getOrDefault(url)
            .take(96)
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
