package com.maodouchat.slim

import java.io.File

/** 单个 APK 的体积分解。 */
data class ApkReport(
    val apk: File,
    val totalBytes: Long,
    val buckets: List<SizeBucket>,
    val largestSo: List<Pair<String, Long>>,
) {
    fun percent(entry: SizeBucket): Double =
        if (totalBytes <= 0L) 0.0 else entry.bytes * 100.0 / totalBytes
}

/** 一类内容（dex / .so / 资源 / assets / META-INF / 其他）的累计体积。 */
data class SizeBucket(val label: String, var bytes: Long = 0L)
