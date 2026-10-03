package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot 用户侧管理（`PUT /api/bots/{botId}/enabled`，`BotManagementRouting.kt`）
 * 请求体 `enabled` 抽取收敛为纯函数（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧
 * 专项评估，G355 `sendMessage` 起至 `POST /api/bots` 之后**第八十一块**）。
 *
 * 本端点的抽取语义与之前的块**逐字不同**，必须原样钉住：
 *
 * - 抽取链 `runCatching { Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive }`
 *   覆盖 parse + 顶层对象断言 + 字段抽取：坏 JSON / 顶层非对象 /
 *   `enabled` 为对象或数组（`?.jsonPrimitive` 处抛 `IllegalArgumentException`）
 *   统统被吞 → `null` → 处理器报 400 `"enabled required"`。
 *   注意这里没有单独的 `"invalid json"` 判定（POST `/api/bots` 是先判 `jsonObject`
 *   再报 `"invalid json"`），坏 JSON 在本端点与「字段缺席」同文案。
 * - 抽取返回 `Boolean?`：`null` 的含义是「不可用」——处理器侧 `?:` 直接
 *   400 `"enabled required"`，文案逐字；纯函数只负责抽取，不碰响应。
 * - 布尔字面量：`true`/`false` → 同值。`booleanOrNull` 即
 *   `content.toBooleanStrictOrNull()`（大小写敏感）。
 * - 字符串型：仅 `"true"`/`"false"` 有效（`"TRUE"`、`"True"`、`"1"`、`"yes"`、
 *   空串 → `null` → 400，大声失败的逐字语义）。
 * - 显式 JSON null：`JsonNull` 是 `JsonPrimitive`，`content` 为字面量 `"null"`，
 *   `toBooleanStrictOrNull("null")` 为 null → `null` → 400
 *   （与 webhook 管理块不同：那里显式 null 得字面量 `"null"` 字符串继续走白名单，
 *   这里显式 null 直接 400——逐字怪语义）。
 * - 数字 / 浮点型：`1` → `content` 为 `"1"` → `null` → 400。
 *
 * 与原处理器逐行等价：维护模式（`rejectIfMaintenance`）、用户身份
 * （`requireUserId`）、封禁拦截（`rejectIfSuspended`）、`botId` 参数校验在原处理器里
 * 先于 body 解析，本轮保持它们在解析之前；`BotRepository.setEnabled`/`bot` 响应
 * 仍在处理器，顺序与原处理器一致——下游一行不动。
 */
internal fun parseManagementBotEnabled(body: String): Boolean? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject + 抽取整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象、enabled 对象/数组型）都得 null（吞掉），
    // 处理器再把 null 报成 400 "enabled required"——逐字怪语义。
    return runCatching {
        val p = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
        p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
    }.getOrNull()
}
