package com.maodouchat.domain.messaging

// 缓存字节数 → "12 KB" 式整数档位文本；与 MediaCenterDisplay.formatBytes 不同
// （后者一位小数、无 GB 档），不收敛。负数按原样落入 B 档。
object CacheSizeDisplay {
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024L * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024L * 1024 * 1024) return "${bytes / (1024L * 1024)} MB"
        return "${bytes / (1024L * 1024 * 1024)} GB"
    }
}
