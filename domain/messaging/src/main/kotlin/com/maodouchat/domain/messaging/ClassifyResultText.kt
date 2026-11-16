package com.maodouchat.domain.messaging

import com.maodouchat.core.model.CategoryCount

// 消息分类复制文本：逐行"<标签>: <N>条\n" + 末尾免责声明；标签与条数文案由调用方按种类传入。
fun classifyResultText(
    categories: List<CategoryCount>,
    labelText: (AiClassifyCategoryKind) -> String,
    countText: (count: Int) -> String,
    disclaimer: String,
): String = buildString {
    categories.forEach { row ->
        append(labelText(aiClassifyCategoryKind(row.category)))
            .append(": ")
            .append(countText(row.count))
            .append('\n')
    }
    append(disclaimer)
}
