package com.maodouchat.domain.conversation

import org.json.JSONObject

// 公屏状态横幅纯拼装：服务端下发字段→显示文本（null 表示不显示）。
// optString 会把显式 JSON null 读成字面 "null"，safeJsonString 统一排除；
// 维护文案优先，其余各段用 " · " 连接；"maintenance" 优先、否则兼容旧键 "maintenanceMode"。
fun buildPublicStatusBanner(o: JSONObject): String? {
    val banner = safeJsonString(o, "banner").ifBlank { safeJsonString(o, "globalBanner") }
    val e2eeBanner = safeJsonString(o, "forceE2eeBanner").ifBlank { safeJsonString(o, "e2eeBanner") }
    val announcement = safeJsonString(o, "publicAnnouncement").ifBlank { safeJsonString(o, "announcement") }
    val maintMsg = safeJsonString(o, "maintenanceMessage")
    val publicMaintenance =
        if (o.has("maintenance")) o.optBoolean("maintenance", false)
        else o.optBoolean("maintenanceMode", false)
    val minApp = safeJsonString(o, "minAppVersion")
    val pqxdh = o.optBoolean("pqxdhPreview", false)
    val upgradeHint = if (minApp.isNotBlank() && minApp != "0") "Min version: $minApp" else null
    val pqxdhHint = if (pqxdh) "PQXDH preview on" else null
    val parts = listOfNotNull(
        banner.takeIf { it.isNotBlank() },
        e2eeBanner.takeIf { it.isNotBlank() },
        announcement.takeIf { it.isNotBlank() },
        upgradeHint,
        pqxdhHint,
    )
    return when {
        publicMaintenance && maintMsg.isNotBlank() -> maintMsg
        parts.isNotEmpty() -> parts.joinToString(" · ")
        else -> null
    }
}

private fun safeJsonString(o: JSONObject, key: String): String =
    if (o.has(key)) o.optString(key).takeIf { it != "null" }.orEmpty() else ""
