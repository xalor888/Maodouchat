package com.maodouchat.domain.messaging

// 回复建议行清洗：逐行 trim、剥行首序号/项目符号、去空行、按请求数取前 N（1~4）；
// 模型调用仍在 app 侧，这里只做返回文本的规整。
object AiSuggestionCleanupPolicy {

    fun cleanSuggestions(raw: String, count: Int): List<String> =
        raw.lineSequence()
            .map { it.trim().trimStart('-', '*', '•', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '.', ')', ' ') }
            .filter { it.isNotBlank() }
            .take(count.coerceIn(1, 4))
            .toList()
}
