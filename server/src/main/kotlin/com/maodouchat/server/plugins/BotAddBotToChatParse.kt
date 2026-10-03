package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot 用户侧交互（`POST /api/chats/{chatId}/bots`，`BotInteractionRouting.kt`）
 * 请求体 `botId` 抽取收敛为纯函数（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage` 起至 `POST /api/bots` 之后**第八十三块**）。
 *
 * 本端点的抽取语义与之前的块**逐字不同**，必须原样钉住：
 *
 * - 抽取链 `runCatching { Json.parseToJsonElement(body).jsonObject["botId"]?.jsonPrimitive?.content }`
 *   覆盖 parse + 顶层对象断言 + 字段抽取：坏 JSON / 顶层非对象 /
 *   `botId` 为对象或数组（`?.jsonPrimitive` 处抛 `IllegalArgumentException`）
 *   统统被吞 → `null` → `.orEmpty()` → `""` → 处理器报 400 `"botId required"`。
 *   注意这里没有单独的 `"invalid json"` 判定（本端点沿用 bot 侧
 *   `POST /api/chats/{chatId}/bot-inbox` 的静默链风格：坏 JSON 与「字段缺席」
 *   同文案——但两者都走到 400，只是这里是 `"botId required"`）。
 * - 抽取返回 `String`（非空保证由 `.orEmpty()` 给出）：`""` 的含义是「不可用」——
 *   处理器侧 `botId.isBlank()` 直接 400 `"botId required"`，文案逐字；
 *   纯函数只负责抽取，不碰响应。
 * - **大声失败**（与 bot-inbox 块的 `as?` 静默**反证**）：`botId` 为对象或数组时
 *   `?.jsonPrimitive` 抛异常被 `runCatching` 吞掉 → `""` → 400——
 *   不是 `""` 静默得、也不是字面量继续走。
 * - 显式 JSON null：`JsonNull` 是 `JsonPrimitive`，其 `content` 为字面量 `"null"`
 *   （**非空**，不被 `isBlank()` 滤掉）→ 不走 400 文案，而是继续走下游
 *   `addOwnedBot(..., "null", ...)` → `BOT_NOT_FOUND` → 404 `"bot not found"`。
 *   与 enabled 块的「显式 null 直接 400」不同——逐字怪语义，本端点沿用
 *   webhook 管理 / bot-inbox 块的「`"null"` 字面量继续走」习惯。
 * - 字符串型：原样返回 content（含首尾空白，空白判在处理器 `isBlank()` 侧）。
 *   数字 / 布尔型：`?.jsonPrimitive?.content` 取其字面量 content
 *   （`7` → `"7"`，`true` → `"true"`），非空即继续走下游。
 * - 近似字段名（`botid` / `BOTID` / `botId2` / `bot_id`）一律忽略 → `""` → 400，
 *   大小写与下划线均不匹配——与原处理器 `jsonObject["botId"]` 逐字一致。
 * - `body` 上限 `receiveBoundedTextOrEmpty(4_096)`、`chatId` 参数校验、
 *   维护模式 / 封禁拦截 / 功能开关、`botId.length > 80` 的 400 判定、
 *   `addOwnedBot` 结果映射（`BOT_NOT_FOUND` 等）全部仍在处理器，
 *   顺序与原处理器一致——下游一行不动。
 */
internal fun parseAddBotToChatBotId(body: String): String {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject + 抽取整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象、botId 对象/数组型）都得 ""（吞掉），
    // 处理器再把空白报成 400 "botId required"——逐字怪语义。
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["botId"]?.jsonPrimitive?.content
    }.getOrNull().orEmpty()
}
