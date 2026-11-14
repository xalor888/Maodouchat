package com.maodouchat.domain.messaging

import java.text.Normalizer
import java.util.Locale

// 消息搜索分词器：索引与查询两端共用的分词契约（原 app 侧 MessageSearchRepository 内联实现，逐字平移）。
object MessageSearchTokenizer {
    fun tokens(value: String): List<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .take(4_000)
        val result = LinkedHashSet<String>()
        val latin = StringBuilder()
        val han = StringBuilder()

        fun flushLatin() {
            if (latin.isEmpty()) return
            val word = latin.toString().take(40)
            result += word
            if (word.length > 3) {
                for (length in 3..minOf(8, word.length - 1)) result += word.take(length)
            }
            latin.clear()
        }

        fun flushHan() {
            if (han.isEmpty()) return
            val run = han.toString()
            run.forEach { result += it.toString() }
            for (size in 2..3) {
                if (run.length >= size) {
                    for (index in 0..run.length - size) result += run.substring(index, index + size)
                }
            }
            han.clear()
        }

        normalized.forEach { char ->
            when {
                isHan(char) -> {
                    flushLatin()
                    han.append(char)
                }
                char.isLetterOrDigit() -> {
                    flushHan()
                    latin.append(char)
                }
                else -> {
                    flushLatin()
                    flushHan()
                }
            }
            // 9.235：达上限直接返回——此前 return@forEach 仅跳过当前字符，
            // 超长文本（上限 4000 字符）仍会白遍历剩余字符且缓冲区不再 flush
            if (result.size >= 200) return result.take(200)
        }
        flushLatin()
        flushHan()
        return result.take(200)
    }

    private fun isHan(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
