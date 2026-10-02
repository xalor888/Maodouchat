package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `setWebhook` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `deleteUpdates` 之后**第七十块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把 `/api/bot/setWebhook`
 * 处理器里内联的**抽取 / trim + take(500) / 白名单校验**逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - `obj` 可为 `null`：body 不是 JSON 对象时 `runCatching { ...jsonObject }.getOrNull()`
 *   得 `null`；本端点**有** `"invalid json"` 400（原处理器逐字如此）→ [InvalidJson]；
 * - 抽取 `obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)`：**trim 在 take(500) 之前**，
 *   超长 URL 先去空白再截断；白名单校验看到的是截断后的值（原处理器逐字如此）；
 * - `url` 缺席 → `null` → [Ok]（下游 `setWebhookByToken(bot.id, null)` 清 webhook，
 *   本轮不动）；空字符串 / 纯空白 → `isNullOrBlank()` 为真 → **跳过白名单校验** → [Ok]
 *   原样（原处理器逐字如此）；
 * - 显式 JSON null → `JsonNull` 是 `JsonPrimitive`，不抛，`.content` 得字面量 `"null"`→
 *   trim 后仍为 `"null"`（非空）→ **走白名单校验**（`"null"` 不是合法 URL → [InvalidUrl]，
 *   原处理器逐字如此）；
 * - 对象 / 数组型在 `?.jsonPrimitive` 处抛 [IllegalArgumentException]
 *   （大声失败，路由层 `StatusPages` 映射为 400「参数无效」，不是 500；
 *   抽取在白名单校验之前，坏类型先抛，不走 [InvalidUrl]，本轮用测试钉住）；
 * - 数字 / 布尔型 → `JsonPrimitive.content` 得 `"123"` / `"true"` → 走白名单校验
 *   （大概率 [InvalidUrl]，原处理器逐字如此）；
 * - 白名单校验器以 `(String) -> Boolean` 注入（生产侧传
 *   `BotRepository::isAllowedWebhookUrl`），纯函数不直接依赖 repository——
 *   只负责抽取与校验，不碰仓库 / 限流 / 响应；限流（`requireRateLimitedBot`）
 *   在原处理器里**先于** body 解析，本轮保持它在解析之前，等价；
 *   `setWebhookByToken`/`logCommand`/响应仍在处理器，顺序与原处理器一致，
 *   逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSetWebhookFields(
    val url: String?,
)

internal sealed interface BotSetWebhookFieldsResult {
    data class Ok(val fields: BotSetWebhookFields) : BotSetWebhookFieldsResult
    /** 400 `"invalid json"` 的前件：body 不是 JSON 对象（`obj` 为 null）。 */
    data object InvalidJson : BotSetWebhookFieldsResult
    /** 400 `"invalid webhook url"` 的前件：url 非空且白名单校验不通过。 */
    data object InvalidUrl : BotSetWebhookFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + trim/take(500) + 白名单校验。
 * 单字段端点：`obj` 为 null（body 非 JSON 对象）→ InvalidJson；
 * `obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)`（trim 先于 take；
 * 显式 null 得字面量 `"null"`；对象/数组型在 `?.jsonPrimitive` 处先抛）；
 * url 非空才走白名单校验，不通过 → InvalidUrl（处理器侧 400，文案逐字）。
 */
internal fun parseBotSetWebhookFields(
    obj: JsonObject?,
    isUrlAllowed: (String) -> Boolean,
): BotSetWebhookFieldsResult {
    if (obj == null) return BotSetWebhookFieldsResult.InvalidJson
    // 注意：抽取在白名单校验之前；显式 null url → 字面量 "null"（非空）→ 走校验；
    // 空字符串 / 纯空白跳过校验；对象/数组型 url 在 ?.jsonPrimitive 处先抛。
    val url = obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)
    if (!url.isNullOrBlank() && !isUrlAllowed(url)) return BotSetWebhookFieldsResult.InvalidUrl
    return BotSetWebhookFieldsResult.Ok(BotSetWebhookFields(url))
}
