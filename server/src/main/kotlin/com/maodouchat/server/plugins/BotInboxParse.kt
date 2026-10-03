package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bot 用户侧交互（`POST /api/chats/{chatId}/bot-inbox`，`BotInteractionRouting.kt`）
 * 请求体 `text` / `botIdHint` 抽取收敛为纯函数（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage` 起至 `PUT /api/bots/{botId}/enabled` 之后
 * **第八十二块**）。
 *
 * 本端点的抽取语义逐字搬移，怪语义原样钉住：
 *
 * - `text = (obj["text"] as? JsonPrimitive)?.content.orEmpty()`：
 *   缺席 → `""`；显式 JSON null → 字面量 `"null"`（`JsonNull` 是 `JsonPrimitive`，
 *   强制转型不失败）；对象 / 数组型 → `""`（`as?` 转型失败是**静默**的，
 *   与 `?.jsonPrimitive` 的大声失败不同——本端点沿用静默习惯）；
 *   数字 / 布尔型 → 其字面量 content（如 `1` → `"1"`，`true` → `"true"`）；
 * - `botIdHint = (obj["botId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }`：
 *   缺席 / 空串 / 全空白 → `null`；显式 null → `"null"`（非空字面量，照旧）；
 *   对象 / 数组型 → `null`（静默）；
 * - 纯函数只负责抽取：坏 JSON 的 `"invalid json"` 判定、`sanitizeInboxText` 的
 *   「命令无效或不能是密文」校验、限流 / 鉴权 / 入群检查 / 投递仍在处理器，
 *   顺序与原处理器一致——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 坏 JSON 关口在解析前、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotInboxFields(
    val text: String,
    val botIdHint: String?,
)

/**
 * 与原处理器逐字一致的 `text` / `botIdHint` 抽取。
 * 注意 `as? JsonPrimitive` 的静默转型：非 primitive 值不抛，
 * `text` 得 `""`、`botIdHint` 得 `null`。
 */
internal fun parseBotInboxFields(obj: JsonObject): BotInboxFields {
    val text = (obj["text"] as? JsonPrimitive)?.content.orEmpty()
    val botIdHint = (obj["botId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    return BotInboxFields(text, botIdHint)
}
