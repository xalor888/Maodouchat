package com.maodouchat.domain.messaging

import com.maodouchat.core.model.NotificationCenterItem

// 通知中心按日分组：TODAY / YESTERDAY / WEEK / EARLIER。自 app 侧
// NotificationCenterFilters.kt 逐字平移；displayLabel() 是 R.string
// 薄壳，仍留在 app 侧（同名扩展函数）。
fun List<NotificationCenterItem>.groupByDay(
    nowMillis: Long = System.currentTimeMillis()
): List<Pair<StringBucket, List<NotificationCenterItem>>> {
    val dayMs = 24L * 3600L * 1000L
    return this
        .groupBy { item ->
            // 8.48 修复 M5：未来时间戳（时钟超前/服务端未来时间）强制归 TODAY——
            // 此前负 diffDays 落入 YESTERDAY/WEEK，未来 8 天以上也归 WEEK
            val diffDays = ((nowMillis - item.updatedAt) / dayMs).coerceAtLeast(0L)
            when {
                diffDays == 0L -> StringBucket.TODAY
                diffDays == 1L -> StringBucket.YESTERDAY
                diffDays <= 7L -> StringBucket.WEEK
                else -> StringBucket.EARLIER
            }
        }
        .map { (bucket, list) ->
            val ordered = list.sortedByDescending { it.updatedAt }
            bucket to ordered
        }
        .sortedBy { it.first.ordinal }
}

enum class StringBucket(val sortOrder: Int) {
    TODAY(0), YESTERDAY(1), WEEK(2), EARLIER(3)
}
