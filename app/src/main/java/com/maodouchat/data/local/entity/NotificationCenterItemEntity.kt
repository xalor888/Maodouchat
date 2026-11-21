package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.maodouchat.domain.messaging.NotificationCenterExtraJsonPolicy

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

fun com.maodouchat.core.model.NotificationCenterItem.toEntity(ownerUserId: String): NotificationCenterItemEntity {
    val extraJson = NotificationCenterExtraJsonPolicy.encode(extra)
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

fun NotificationCenterItemEntity.toModel(): com.maodouchat.core.model.NotificationCenterItem {
    val extraMap = NotificationCenterExtraJsonPolicy.decode(extraJson)
    return com.maodouchat.core.model.NotificationCenterItem(
        id = id,
        type = type,
        mergeKey = mergeKey,
        title = title,
        subtitle = subtitle,
        preview = preview,
        deeplink = deeplink,
        extra = extraMap,
        read = read,
        count = NotificationCenterExtraJsonPolicy.displayCount(count),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
