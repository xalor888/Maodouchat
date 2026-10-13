package com.maodouchat.server.service

import com.maodouchat.server.model.NotificationSettingsResponse
import java.time.Instant
import java.time.ZoneOffset

// 投递策略簇：纯函数，无实例状态。TTL/重试判定/勿扰/死 token 识别，单测直接钉住。

private const val MESSAGE_TTL = "86400s"
private const val CALL_TTL = "60s"

fun isTransientHttpFailure(status: Int): Boolean = status == 429 || status >= 500

fun ttlFor(type: String?): String =
    if (type == "INCOMING_CALL") CALL_TTL else MESSAGE_TTL

fun isInDoNotDisturb(
    settings: NotificationSettingsResponse,
    offsetMinutes: Int,
    nowMillis: Long
): Boolean {
    if (!settings.dndEnabled) return false
    val start = settings.dndStartMinute.coerceIn(0, 1439)
    val end = settings.dndEndMinute.coerceIn(0, 1439)
    if (start == end) return false
    val safeOffset = offsetMinutes.coerceIn(-18 * 60, 18 * 60)
    val local = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.ofTotalSeconds(safeOffset * 60))
    val minute = local.hour * 60 + local.minute
    return if (start < end) minute in start until end else minute >= start || minute < end
}

fun isPermanentTokenFailure(status: Int, body: String): Boolean {
    if (status != 400 && status != 404) return false
    val text = body.lowercase()
    if ("unregistered" in text ||
        "not_found" in text ||
        "notfound" in text ||
        "registration-token-not-registered" in text ||
        "requested entity was not found" in text ||
        "sender_id_mismatch" in text ||
        "senderidmismatch" in text
    ) {
        return true
    }
    val invalidArg = "invalid_argument" in text || "invalidargument" in text
    return invalidArg && ("registration" in text || "token" in text)
}
