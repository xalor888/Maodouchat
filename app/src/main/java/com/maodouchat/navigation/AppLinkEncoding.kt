package com.maodouchat.navigation

/** RFC3986 unreserved 保持原样，其余 UTF-8 百分比编码（与 Uri.encode 默认行为对齐）。 */
internal fun AppLinkRouter.encodePathSegment(raw: String): String {
    val sb = StringBuilder()
    // 按字符而非字节判断 unreserved，避免多字节字符被误保留。
    for (ch in raw) {
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            sb.append(ch)
        } else {
            for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                sb.append('%')
                sb.append(HEX[(b.toInt() shr 4) and 0xF])
                sb.append(HEX[b.toInt() and 0xF])
            }
        }
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()

internal fun AppLinkRouter.decodeComponent(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = HEX.indexOf(raw[i + 1].uppercaseChar())
            val lo = HEX.indexOf(raw[i + 2].uppercaseChar())
            if (hi >= 0 && lo >= 0) {
                out.append(((hi shl 4) or lo).toChar())
                i += 3
                continue
            }
        }
        out.append(if (c == '+') ' ' else c)
        i++
    }
    return out.toString()
}
