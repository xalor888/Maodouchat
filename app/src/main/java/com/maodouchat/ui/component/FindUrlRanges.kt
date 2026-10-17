package com.maodouchat.ui.component

/** 扫描文本中的 http/https URL 起止区间（左闭右开），供 [RichTextContent] 渲染可点击链接。 */

// ─── findUrlRanges ───
internal fun findUrlRanges(text: String): List<Pair<Int, Int>> {
    val ranges = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < text.length) {
        val start = if (text.startsWith("http://", i) || text.startsWith("https://", i)) i else -1
        if (start < 0) { i++; continue }
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in setOf('<', '>', '"', '\'')) {
            end++
        }
        while (end > start && text[end - 1] in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) {
            end--
        }
        if (end > start) ranges += start to end
        i = end.coerceAtLeast(start + 1)
    }
    return ranges
}
