package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocationPayload

// 位置消息载荷构造：精度只在 hasAccuracy 时保留、采集时间 <= 0 回退到 now；
// 静态与实时共用同一映射，实时再叠 label/live/liveUntil/sessionId。
// 入参是原始值而非 android.location.Location——:domain:messaging 是纯 JVM 模块。
object LocationPayloadPolicy {

    fun static(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        hasAccuracy: Boolean,
        time: Long,
        now: Long
    ): LocationPayload = LocationPayload(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.takeIf { hasAccuracy },
        capturedAt = time.takeIf { it > 0 } ?: now
    )

    fun live(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        hasAccuracy: Boolean,
        time: Long,
        now: Long,
        label: String,
        liveUntil: Long,
        sessionId: String
    ): LocationPayload = static(
        latitude, longitude, accuracy, hasAccuracy, time, now
    ).copy(
        label = label,
        live = true,
        liveUntil = liveUntil,
        sessionId = sessionId
    )
}
