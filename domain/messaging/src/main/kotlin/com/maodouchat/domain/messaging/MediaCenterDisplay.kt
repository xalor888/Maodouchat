package com.maodouchat.domain.messaging

import java.util.Locale

// 媒体中心列表展示纯函数：文件大小格式化，不依赖 Android。
object MediaCenterDisplay {

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // 文件消息大小：与 formatBytes 同款 B/KB/MB 三档，但用 Locale.ROOT（始终小数点）；
    // formatBytes 用默认 locale，两者在逗号小数 locale 下显示不同，故不收敛。
    fun formatPresentationFileSize(bytes: Long): String = when {
        bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}
