package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 管理后台「系统安全快照 + 运营配置」子域路由（`PUT /settings`，
 * 在 `AdminSystemRouting.kt`）请求体 JSON 对象信封解析收敛为纯函数
 * （清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 admin 侧专项评估
 * **第二块**）。
 *
 * 信封抽取语义逐字钉住：
 *
 * - `runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()`：
 *   坏 JSON → 抛（吞掉）→ `null`；顶层非对象（数组 / 字符串 / 数字 /
 *   布尔 / 显式 JSON null——`JsonNull` 是 `JsonPrimitive`，
 *   `.jsonObject` 处抛 `IllegalArgumentException`）→ 抛（吞掉）→ `null`；
 *   顶层为对象 → 原样返回该 `JsonObject`（不深拷贝、不排序、不过滤键——
 *   未知键全部保留，交由下游字段抽取决定取舍）。
 * - 处理器侧 `?: return@put call.respond(HttpStatusCode.BadRequest,
 *   ErrorResponse("invalid json"))` 保持在处理器里，纯函数只负责解析、
 *   不碰响应，顺序与原处理器一致。
 * - body 读取（`runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()`，
 *   受 `MAX_ADMIN_JSON_BODY_CHARS` 上限，失败兜底空串）仍在处理器，不进纯函数；
 *   空 body → `""` → 抛 → `null` → 400 `"invalid json"`，与原先逐字一致。
 * - 纯函数不做鉴权（`call.isAdminUser()` 在信封解析之前）——下游一行不动
 *   （`settings` 键存在则用其对象、否则整顶层对象作为配置源；
 *   非字串值整行丢弃；更新集为空 → 400 `"no settings"`；
 *   `RuntimeConfigService.setMany(updates, actorId)` + `ADMIN_SETTINGS_UPDATE`
 *   审计，全部仍在处理器，顺序一致）。
 *
 * 评估结论：手写解析本来就满足 fuzz 系列的契约（未知键忽略、坏 JSON 关口在
 * 解析前、无未处理 500），这里只是把它变成可被测试钉住的形态，零行为改动。
 */
internal fun parseAdminSystemJsonEnvelopeOrNull(body: String): JsonObject? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject 整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象）都得 null（吞掉），
    // 处理器再把 null 报成 400 "invalid json"——逐字怪语义。
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
