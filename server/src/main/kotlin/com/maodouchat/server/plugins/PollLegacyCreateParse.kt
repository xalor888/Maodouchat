package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal data class PollLegacyCreateFields(
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
    val closesAt: Long?,
)

// 9.157：非法选项元素整体拒绝——任一元素非原语即 null（路由报 400 "投票选项无效"），
// 不静默截成子集。其余抽取与原处理器逐字一致（含布尔字段的字符串形态兼容）。
internal fun parsePollLegacyCreateOrNull(obj: JsonObject): PollLegacyCreateFields? {
    val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
    val options = buildList {
        val arr = obj["options"]?.jsonArray
        if (arr != null) {
            for (element in arr) {
                val text = (element as? JsonPrimitive)?.content ?: return null
                add(text)
            }
        }
    }
    val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
        ?: obj["multi"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        ?: false
    val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
        ?: obj["anonymous"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        ?: false
    val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
    return PollLegacyCreateFields(question, options, multi, anonymous, closesAt)
}

// 9.157：optionIndexes 数组元素非法（非整数/负数/非原语）或 optionIndex 非法即 null
//（路由报 400 "投票选项无效"），与原处理器逐字一致。
internal fun parsePollLegacyVoteOrNull(obj: JsonObject): List<Int>? {
    return buildList {
        val arr = obj["optionIndexes"]?.jsonArray
        if (arr != null) {
            for (element in arr) {
                val v = (element as? JsonPrimitive)?.content?.toIntOrNull()
                if (v == null || v < 0) return null
                add(v)
            }
        } else {
            val single = (obj["optionIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (single == null || single < 0) return null
            add(single)
        }
    }
}
