package com.maodouchat.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 系统公告展示策略（客户端）。
 *
 * 服务端只下发「当前处于 ACTIVE 窗口且受众命中」的公告（见服务端
 * AdminEnhanceRouting /api/announcements/active），客户端在此基础上做
 * 第二层展示过滤与去重，避免重复弹窗 / 通知轰炸：
 * - 已读（acked）公告不再重复提示；
 * - 同一次拉取内按 level 排序，紧急优先；
 * - 窗口边界由客户端本地时钟再次校验（防缓存导致的过期公告残留）。
 *
 * 本对象为纯函数策略，不持有状态。
 */
object AnnouncementPolicy {

    /** 服务端公告级别 → 客户端展示优先级（数值越小越靠前）。 */
    val LEVEL_PRIORITY: Map<String, Int> = mapOf(
        "EMERGENCY" to 0,
        "MAINTENANCE" to 1,
        "WARNING" to 2,
        "INFO" to 3
    )

    /**
     * 客户端展示过滤：仅保留未读、处于生效窗口、且级别合法的公告。
     *
     * @param announcements 服务端下发的候选列表
     * @param nowMs 客户端本地时钟（毫秒）
     * @param forceShowAcked 为 true 时忽略已读状态（例如用户主动进入公告中心查看历史）
     */
    fun filterForDisplay(
        announcements: List<AnnouncementData>,
        nowMs: Long,
        forceShowAcked: Boolean = false
    ): List<AnnouncementData> {
        return announcements
            .filter { a ->
                (forceShowAcked || !a.acked) &&
                    a.startsAt <= nowMs &&
                    a.expiresAt >= nowMs &&
                    a.status == "ACTIVE" &&
                    a.level in LEVEL_PRIORITY
            }
            .sortedWith(compareBy({ LEVEL_PRIORITY[it.level] ?: 99 }, { it.startsAt }))
    }

    /** 是否需要触发一次前台提示（弹窗/横幅）：存在未读且未过期的高优先级公告。 */
    fun shouldNotifyNow(
        announcements: List<AnnouncementData>,
        nowMs: Long
    ): Boolean =
        filterForDisplay(announcements, nowMs).any { a ->
            a.level == "EMERGENCY" || a.level == "MAINTENANCE"
        }

    private val json = Json { ignoreUnknownKeys = true }

    /** 公告数据模型见 [AnnouncementData]（与服务端 AnnouncementDto 对齐的字段子集，客户端只消费这些）。 */
    /**
     * 解析 `/api/announcements/active` JSON 载荷并做展示过滤。
     * 解析失败返回空列表（调用方保持既有公告不变）。
     */
    fun parseAndFilterActivePayload(raw: String, nowMs: Long = System.currentTimeMillis()): List<AnnouncementData> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val arr = root["announcements"] as? JsonArray ?: return@runCatching emptyList()
            val items = arr.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.safeString("id")
                if (id.isBlank()) return@mapNotNull null
                AnnouncementData(
                    id = id,
                    title = o.safeString("title"),
                    content = o.safeString("content"),
                    level = o.safeString("level"),
                    startsAt = o.safeLong("startsAt"),
                    expiresAt = o.safeLong("expiresAt"),
                    status = o.safeString("status"),
                    acked = o.safeBoolean("acked"),
                )
            }
            filterForDisplay(items, nowMs)
        }.getOrNull().orEmpty()
    }

    private fun JsonObject.safeString(key: String): String {
        val element = this[key] ?: return ""
        if (element is JsonNull) return ""
        if (element is JsonPrimitive) return element.content.takeIf { it != "null" }.orEmpty()
        return element.toString()
    }

    private fun JsonObject.safeLong(key: String): Long {
        val primitive = this[key] as? JsonPrimitive ?: return 0L
        return primitive.longOrNull ?: 0L
    }

    private fun JsonObject.safeBoolean(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: return false
        return primitive.booleanOrNull ?: false
    }
}
