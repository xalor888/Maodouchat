package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface BotHintChatIdResult {
    data class Ok(val chatId: String) : BotHintChatIdResult
    /** 400 `"chatId required"` 的全部前件：chatId 缺/空/纯空白。 */
    data object Invalid : BotHintChatIdResult
}

/**
 * 与原处理器逐字一致的 `chatId` 抽取 + 合并必填校验。
 * `chatId`（无 trim；显式 null 得字面量 `"null"`）——缺/空/纯空白 → Invalid
 * （处理器侧 400 `"chatId required"`）。
 */
internal fun parseBotHintChatId(request: JsonObject): BotHintChatIdResult {
    // 注意：无 trim；chatId 键显式 null → 字面量 "null"（不判空）。
    val chatId = request["chatId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank()) return BotHintChatIdResult.Invalid
    return BotHintChatIdResult.Ok(chatId)
}

/**
 * 与原处理器逐字一致的 `hint` 解析。调用方必须在 `isParticipant` 成员检查
 * **之后**调用（见文件头注释的顺序论证）。
 */
internal fun resolveBotHint(request: JsonObject, sanitize: Boolean, defaultHint: String): String {
    // 注意：hint 键缺席 → null（回退 defaultHint）；显式 null → 字面量 "null"
    // 字符串（不回退）；对象/数组型在 ?.jsonPrimitive 处大声失败。
    val suppliedHint = request["hint"]?.jsonPrimitive?.content
    return if (sanitize) {
        sanitizeBotHint(suppliedHint).ifBlank { defaultHint }
    } else {
        (suppliedHint ?: defaultHint).take(120)
    }
}
