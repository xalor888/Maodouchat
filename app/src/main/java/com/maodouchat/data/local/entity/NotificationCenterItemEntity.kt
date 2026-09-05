package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 通知中心条目（退役 `notification_center` SharedPreferences JSON 存储）。
 * 内存 StateFlow 仍是读写真相源；本表只做跨进程 durable 快照。
 */
@Entity(
    tableName = "notification_center_items",
    primaryKeys = ["ownerUserId", "id"],
    indices = [
        Index(value = ["ownerUserId"]),
        Index(value = ["ownerUserId", "updatedAt"]),
    ],
)
data class NotificationCenterItemEntity(
    val ownerUserId: String,
    val id: String,
    val type: String,
    val mergeKey: String,
    val title: String,
    val subtitle: String?,
    val preview: String?,
    val deeplink: String?,
    /** extra 映射展平为 JSON 字符串（键值均为 String）。 */
    val extraJson: String,
    val read: Boolean,
    val count: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

fun com.maodouchat.data.repository.NotificationCenterItem.toEntity(ownerUserId: String): NotificationCenterItemEntity {
    val extraJson = org.json.JSONObject()
    extra.forEach { (k, v) -> extraJson.put(k, v) }
    return NotificationCenterItemEntity(
        ownerUserId = ownerUserId,
        id = id,
        type = type,
        mergeKey = mergeKey,
        title = title,
        subtitle = subtitle,
        preview = preview,
        deeplink = deeplink,
        extraJson = extraJson.toString(),
        read = read,
        count = count,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun NotificationCenterItemEntity.toModel(): com.maodouchat.data.repository.NotificationCenterItem {
    val extraJson = runCatching { org.json.JSONObject(extraJson) }.getOrNull()
    val extraMap = if (extraJson == null) emptyMap()
    else extraJson.keys().asSequence().associateWith { extraJson.optString(it) }
    return com.maodouchat.data.repository.NotificationCenterItem(
        id = id,
        type = type,
        mergeKey = mergeKey,
        title = title,
        subtitle = subtitle,
        preview = preview,
        deeplink = deeplink,
        extra = extraMap,
        read = read,
        count = count.coerceAtLeast(1),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
