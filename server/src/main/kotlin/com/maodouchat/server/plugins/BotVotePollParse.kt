package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal data class BotVotePollFields(
    val pollId: String,
    val optionIndexes: List<Int>,
)

internal sealed interface BotVotePollFieldsResult {
    data class Ok(val fields: BotVotePollFields) : BotVotePollFieldsResult
    /** 400 `\"invalid optionIndexes\"`：数组元素非法（非整数/负数/非原语）→整体拒绝。 */
    data object InvalidOptionIndexes : BotVotePollFieldsResult
    /** 400 `\"pollId/optionIndexes required\"` 的全部前件：原处理器两处同文案返回的合并。 */
    data object Required : BotVotePollFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * pollId（无 trim）+ optionIndexes/optionIndex（数组优先、非法整体拒绝）。
 */
internal fun parseBotVotePollFields(obj: JsonObject): BotVotePollFieldsResult {
    // 注意：pollId 无 trim；pollId 键显式 null → 字面量 "null"（不判空）。
    val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
    // 9.157：同用户投票端点——非法元素整体拒绝，不静默截成子集投票。
    val indexes = buildList {
        val arr = obj["optionIndexes"] as? JsonArray
        if (arr != null) {
            for (element in arr) {
                val v = (element as? JsonPrimitive)?.content?.toIntOrNull()
                if (v == null || v < 0) return BotVotePollFieldsResult.InvalidOptionIndexes
                add(v)
            }
        } else {
            val single = (obj["optionIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (single == null || single < 0) return BotVotePollFieldsResult.Required
            add(single)
        }
    }
    if (pollId.isBlank() || indexes.isEmpty()) return BotVotePollFieldsResult.Required
    return BotVotePollFieldsResult.Ok(BotVotePollFields(pollId, indexes))
}
