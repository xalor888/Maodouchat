package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendSteps` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `echo` 之后**第七十六块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/sendSteps`
 * 处理器里内联的**抽取 / 合并必填校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `chatId` 取 `obj["chatId"]?.jsonPrimitive?.content.orEmpty()`，**没有 `.trim()`**——
 *   全空白直接判空，原处理器逐字如此；
 * - `title` 取 `(obj["title"]?.jsonPrimitive?.content ?: "Steps").take(80)`：
 *   默认文案 `"Steps"` 只在**键缺席**时回退——显式 JSON null 得字面量 `"null"`
 *   （不回退默认值，特意钉住）；显式空字符串保留空（不回退默认值，逐字语义）；
 *   **没有 `.trim()`**（前导空格计入 80 上限，逐字保留）；
 *   对象 / 数组型 title 在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500）；
 * - `steps` 取
 *   `(obj["steps"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()).map { it.take(160) }.take(20)`：
 *   键缺席→空列表；显式 JSON null / 非数组型在 `?.jsonArray` 处抛
 *   [IllegalArgumentException]（大声失败，逐字语义——`JsonNull` 不是数组）；
 *   数组元素里**非 primitive（对象/数组）静默丢弃**（`runCatching` 包住，
 *   与坏类型大声失败的已知字段**故意不同**，逐字保留），而 `JsonNull` 元素是
 *   `JsonPrimitive`、得字面量 `"null"` 字符串（保留，非空）；
 *   逐项 `.take(160)`、全表 `.take(20)`（先逐项截、再取前 20，逐字顺序）；
 * - **合并必填**（`chatId.isBlank() || steps.isEmpty()`→400 `"chatId/steps required"`，
 *   文案逐字）——注意 steps 为空也可能是「全被静默丢弃」的结果（逐字语义）；
 * - 纯函数只负责抽取与校验，不碰仓库 / 限流 / 响应——限流（`requireRateLimitedBot`）
 *   与 `isMarkdownEnabled` 门禁在原处理器里**先于** body 解析，本轮保持它们在解析
 *   之前，等价；`isParticipant` / `publishBotServiceMessage`（固定 `type="MARKDOWN"`）/
 *   `logCommand`（`"sendSteps"`）/ 响应（`"ok": true`、`messageId`、`"type": "MARKDOWN"`）
 *   仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendStepsFields(
    val chatId: String,
    val title: String,
    val steps: List<String>,
)

internal sealed interface BotSendStepsFieldsResult {
    data class Ok(val fields: BotSendStepsFields) : BotSendStepsFieldsResult
    /** 400 `"chatId/steps required"` 的全部前件：chatId 缺/空/纯空白，或 steps 缺/空/全被丢弃。 */
    data object Invalid : BotSendStepsFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * `chatId`（无 trim）；`title`（缺键→`"Steps"`，逐项取 80）；`steps`（逐项取 160、全表取 20，
 * 非 primitive 元素静默丢弃）——缺/空/纯空白 chatId 或空 steps → Invalid
 * （处理器侧 400 `"chatId/steps required"`）。
 */
internal fun parseBotSendStepsFields(obj: JsonObject): BotSendStepsFieldsResult {
    // 注意：无 trim；chatId 键显式 null → 字面量 "null"（不判空）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：title 缺键才回退 "Steps"；显式 null → 字面量 "null"（不回退）。
    val title = (obj["title"]?.jsonPrimitive?.content ?: "Steps").take(80)
    // 注意：steps 键显式 null / 非数组型在 ?.jsonArray 处大声失败；
    // 数组里的对象/数组元素静默丢弃（runCatching），JsonNull 元素得 "null" 字符串。
    val steps = (obj["steps"]?.jsonArray?.mapNotNull {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    } ?: emptyList()).map { it.take(160) }.take(20)
    if (chatId.isBlank() || steps.isEmpty()) return BotSendStepsFieldsResult.Invalid
    return BotSendStepsFieldsResult.Ok(BotSendStepsFields(chatId, title, steps))
}
