package com.maodouchat.domain.messaging

// 媒体中心列表展示纯函数：文件大小格式化，不依赖 Android。
object MediaCenterDisplay {

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
