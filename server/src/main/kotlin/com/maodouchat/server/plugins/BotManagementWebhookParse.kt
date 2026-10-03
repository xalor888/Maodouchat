package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot 用户侧 webhook 管理（`PUT /api/bots/{botId}/webhook`，`BotManagementRouting.kt`）
 * 请求体 `url` 抽取收敛为纯函数（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `POST /api/bots` 之后**第八十块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把处理器里内联的
 * `url` 抽取 + trim + take(500) 逻辑收敛为纯函数，行为与搬移前逐行一致——
 * 包括几处故意保留的「怪」语义：
 *
 * - 与 Bot API 的 `/api/bot/setWebhook`（[BotSetWebhookParse]，第七十块）**不同**：
 *   这条用户侧端点**不判 `invalid json`**。原处理器把
 *   `Json.parseToJsonElement(body).jsonObject` 整个包在 `runCatching` 里再
 *   `.getOrNull()`：坏 JSON / 顶层非对象 / `url` 对象数组型（`?.jsonPrimitive`
 *   处抛）统统被吞 → `url == null` → 下游 `setWebhook(botId, userId, null)`
 *   即**清空 webhook**——吞异常的逐字怪语义（POST `/api/bots` 那块是
 *   大声失败，这里是吞掉，本轮原样钉住）；
 * - `url` 取 `obj["url"]?.jsonPrimitive?.content`：显式 JSON null → `JsonNull`
 *   是 `JsonPrimitive`，`.content` 得字面量 `"null"`（非空→照常进入白名单校验，
 *   逐字怪语义）；数字 / 布尔型走 `.content`（`123` → `"123"`、
 *   `true` → `"true"`），原处理器逐字如此；
 * - 后处理 `?.trim()?.take(500)`：**trim 先于 take(500)**——超长 URL 先去空白
 *   再截断；白名单校验看到的是截断后的值（原处理器逐字如此）；
 * - `url` 缺席 / 坏 JSON / 坏类型 → `null`；空字符串 / 纯空白 → `""`：
 *   下游 `isNullOrBlank()` 为真 → **跳过白名单校验** → 原样清空 webhook
 *   （原处理器逐字如此）；
 * - 纯函数只负责抽取，不碰仓库 / 白名单校验 / 响应——维护模式
 *   （`rejectIfMaintenance`）、用户身份（`requireUserId`）、封禁拦截
 *   （`rejectIfSuspended`）、`botId` 参数校验在原处理器里**先于** body 解析，
 *   本轮保持它们在解析之前，等价；`BotRepository.isAllowedWebhookUrl` 白名单校验
 *   仍在处理器里，顺序与原处理器一致，逐行等价——下游一行不动。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型不 500），这里只是把它变成可被测试钉住的形态，零行为改动。
 */
internal fun parseManagementWebhookUrl(body: String): String? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject + 抽取整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象、url 对象/数组型）都得 null（吞掉），
    // 与 BotSetWebhookParse 的大声失败不同——逐字怪语义。
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content
    }.getOrNull()?.trim()?.take(500)
}
