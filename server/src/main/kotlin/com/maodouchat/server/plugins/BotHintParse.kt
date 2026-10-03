package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot hint 端点群（`BotHintRouting.kt` 的 `BOT_HINT_SPECS` 共享循环，
 * 35 个 `/api/bot/send*Hint`）请求体解析（清单 Q01「协议模型向前/向后兼容与
 * fuzz 测试」的 bot 侧专项评估，G355 `sendMessage` 起至 `echo` 之后**第七十七块**）。
 *
 * Bot hint 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把共享循环体里内联的
 * **抽取 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的
 * 「怪」语义：
 *
 * - `chatId` 取 `request["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；
 *   显式 JSON null 得字面量 `"null"`（非空→通过必填，逐字怪语义）；
 *   对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - 合并必填（`chatId.isBlank()`→400 `"chatId required"`，文案逐字）——
 *   纯函数合并为一种 `Invalid` 结果；
 * - `hint` 取 `request["hint"]?.jsonPrimitive?.content`：键缺席→`null`；
 *   显式 JSON null 得字面量 `"null"` 字符串（**不**回退 `defaultHint`，逐字语义）；
 *   对象 / 数组型在 `?.jsonPrimitive` 处大声失败（逐字语义）；
 *   `sanitize=true` 走 `sanitizeBotHint(suppliedHint).ifBlank { defaultHint }`
 *   （缺键时 `sanitizeBotHint(null)` 为空→回退默认值，逐字）；
 *   `sanitize=false` 走 `(suppliedHint ?: defaultHint).take(120)`（逐字）；
 * - **拆成两个纯函数的原因**：原处理器里 `hint` 的解析在 `isParticipant`
 *   （403 `"bot not in chat"`）**之后**。若把 chatId 与 hint 合并成一个函数，
 *   「hint 坏类型 + 调用者非成员」的组合会从原处理器的 403 提前变成 400
 *   （`IllegalArgumentException` 大声失败先于成员检查）——逐行等价要求保持
 *   求值顺序，因此 chatId 校验与 hint 解析各为一个纯函数，路由侧按原顺序调用，
 *   下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
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
