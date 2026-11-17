package com.maodouchat.domain.messaging

// 图片缓存键纯格式：按登录账号隔离，避免串图；Coil 请求组装仍在 app 侧。
object OwnerScopedImageKeyPolicy {
    fun formatKey(ownerUserId: String, data: String): String? {
        if (data.isBlank()) return null
        return "$ownerUserId:$data"
    }
}
