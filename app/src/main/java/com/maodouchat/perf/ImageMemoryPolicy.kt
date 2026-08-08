package com.maodouchat.perf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap

/**
 * 图片内存预算（B7 性能预算：单图解码 ≤ 2048x2048，Coil 内存缓存 堆 20% / 低内存 15%）。
 *
 * 与 `MaodouchatApp` 中 Coil `ImageLoader` 的配置保持一致：
 * - `maxMemoryBytes <= 192MB` 判为低内存设备，缓存上限取堆的 15%；
 * - 其余设备取 20%；
 * - 磁盘缓存固定 100MB。
 *
 * 本对象提供与上述规则同源的策略函数与单图解码预算，供图片加载、缩略图生成、
 * 内存水位告警等场景复用；单图解码在 ZoomableAsyncImage 侧已限制 ≤ 2048x2048，
 * 这里给出统一的采样因子（power-of-2）计算方式，避免缩略图链路各自硬编码。
 */
object ImageMemoryPolicy {

    /** 单张图片最大解码边长（px），超出即降采样。 */
    const val MAX_DECODE_DIMENSION = 2048

    /** 低内存设备判定阈值：应用可用堆 ≤ 192MB 视为低内存。 */
    const val LOW_MEMORY_HEAP_THRESHOLD_BYTES = 192L * 1024 * 1024

    /** 常规设备 Coil 内存缓存占堆比例。 */
    const val MEMORY_CACHE_PERCENT_NORMAL = 0.20

    /** 低内存设备 Coil 内存缓存占堆比例。 */
    const val MEMORY_CACHE_PERCENT_LOW = 0.15

    /** Coil 磁盘缓存上限（100MB）。 */
    const val DISK_CACHE_BYTES = 100L * 1024 * 1024

    /** 应用可用堆大小（字节），来自 ActivityManager.getMemoryClass。 */
    fun heapBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 192L * 1024 * 1024
        return am.memoryClass.toLong() * 1024 * 1024
    }

    /** 是否低内存设备（堆 ≤ 192MB）。 */
    fun isLowMemoryDevice(context: Context): Boolean = heapBytes(context) <= LOW_MEMORY_HEAP_THRESHOLD_BYTES

    /** Coil 内存缓存占堆比例（与 MaodouchatApp 的 ImageLoader 配置一致）。 */
    fun memoryCachePercent(context: Context): Double =
        if (isLowMemoryDevice(context)) MEMORY_CACHE_PERCENT_LOW else MEMORY_CACHE_PERCENT_NORMAL

    /** Coil 内存缓存上限（字节）。 */
    fun memoryCacheBytes(context: Context): Long =
        (heapBytes(context).toDouble() * memoryCachePercent(context)).toLong()

    /** 按 [Bitmap.Config] 估算单张位图占用（字节）。 */
    fun decodedByteSize(width: Int, height: Int, config: Bitmap.Config): Int {
        val bytesPerPixel = when (config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.HARDWARE -> 4 // 不在应用堆计（GPU 侧），按 4BPP 近似展示开销
            else -> 4
        }
        return width * height * bytesPerPixel
    }

    /**
     * 把源图降采样到 ≤ [MAX_DECODE_DIMENSION] 的 power-of-2 采样因子。
     * 返回 1 表示无需降采样。配合 BitmapFactory.Options.inSampleSize 使用。
     */
    fun targetSampleSize(srcWidth: Int, srcHeight: Int): Int {
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= MAX_DECODE_DIMENSION) return 1
        var sample = 1
        while (longest / (sample * 2) >= MAX_DECODE_DIMENSION) sample *= 2
        return sample
    }

    /** 解码后的图片是否值得进入内存缓存（超过单图预算的降采样图不入缓存）。 */
    fun canCacheDecoded(width: Int, height: Int): Boolean =
        width <= MAX_DECODE_DIMENSION && height <= MAX_DECODE_DIMENSION

    /**
     * 当前单图解码预算（字节）：以最大解码边长估算 ARGB_8888 占用。
     * 用于缩略图链路判断是否应进一步压缩（如贴纸/预览图）。
     */
    val maxDecodedByteSize: Int = MAX_DECODE_DIMENSION * MAX_DECODE_DIMENSION * 4

    /** 线程内缓存位图时的建议复用尺寸（避免 decode 时反复分配）。 */
    fun suggestReusableSize(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Int =
        decodedByteSize(width, height, config)

    /** 是否可在应用堆缓存该图（对比 Coil 内存缓存上限，超出直接跳过缓存只做磁盘缓存）。 */
    fun canCacheInHeap(context: Context, width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Boolean {
        if (!canCacheDecoded(width, height)) return false
        return decodedByteSize(width, height, config).toLong() * 4 <= memoryCacheBytes(context)
    }
}
