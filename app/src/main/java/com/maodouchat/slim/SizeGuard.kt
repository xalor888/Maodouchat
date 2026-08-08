package com.maodouchat.slim

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 构建期体积统计辅助（B1 区块 · 包体瘦身与构建基线，2026-08-01）。
 *
 * 纯 JVM 实现（不依赖 Android），读取 Release APK 输出文件并打印体积分解：
 * dex / 原生 .so / 资源 / assets / META-INF 各占多少字节与百分比，便于定位膨胀来源。
 *
 * 用法（由 Gradle 任务 `:app:verifyReleaseSize` 包装，主控重建后直接调用）：
 * ```
 * java -cp <app-classes>:<deps> com.maodouchat.slim.SizeGuard <apk目录> [基线字节数]
 * ```
 * - 第 1 个参数：APK 所在目录（默认取 build/outputs/apk/release）。
 * - 第 2 个参数（可选）：体积基线字节数（默认 10MB = 10 * 1024 * 1024）。
 * - 超过基线时打印分解表并以非零退出码结束（供 CI/护栏阻断发版）。
 */
object SizeGuard {

    /** 默认基线：10MB（feature-vision B1 目标 APK ≤ 10MB）。 */
    const val DEFAULT_BASELINE_BYTES: Long = 10L * 1024L * 1024L

    /** 优先候选的 APK 文件名（按优先级）。 */
    private val PREFERRED_APK_NAMES = listOf(
        "app-release.apk",
        "app-release-unsigned.apk",
    )

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

    /** 在目录中挑选待检查的 APK：优先已知文件名，否则取最新的 *.apk。 */
    fun apkCandidates(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val apks = dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val preferred = PREFERRED_APK_NAMES.mapNotNull { name -> apks.firstOrNull { it.name == name } }
        return (preferred + apks).distinctBy { it.absolutePath }
    }

    /** 解析 APK（zip）并归类各条目体积。 */
    fun analyzeApk(apk: File): ApkReport {
        val buckets = listOf(
            SizeBucket("dex"),
            SizeBucket("native(.so)"),
            SizeBucket("resources(res/)"),
            SizeBucket("assets"),
            SizeBucket("META-INF"),
            SizeBucket("other"),
        )
        val soSizes = mutableMapOf<String, Long>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement() as ZipEntry
                if (entry.isDirectory) continue
                val size = entry.size.coerceAtLeast(0L)
                val name = entry.name
                when {
                    name.startsWith("classes") && name.endsWith(".dex") -> buckets[0].bytes += size
                    name.startsWith("lib/") && name.endsWith(".so") -> {
                        buckets[1].bytes += size
                        soSizes.merge(name, size, Long::plus)
                    }
                    name.startsWith("res/") -> buckets[2].bytes += size
                    name.startsWith("assets/") -> buckets[3].bytes += size
                    name.startsWith("META-INF/") -> buckets[4].bytes += size
                    else -> buckets[5].bytes += size
                }
            }
        }
        val largestSo = soSizes.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key to it.value }
        return ApkReport(
            apk = apk,
            totalBytes = apk.length(),
            buckets = buckets,
            largestSo = largestSo,
        )
    }

    /** 是否在基线内。 */
    fun isWithinBaseline(report: ApkReport, baselineBytes: Long = DEFAULT_BASELINE_BYTES): Boolean =
        report.totalBytes <= baselineBytes

    /** 格式化完整分解报告（多行文本，含每个桶的字节数、占比与最大的 .so）。 */
    fun formatReport(report: ApkReport): String = buildString {
        val mb = report.totalBytes / (1024.0 * 1024.0)
        appendLine("=== Maodouchat Release APK 体积分解 ===")
        appendLine("APK   : ${report.apk.absolutePath}")
        appendLine("总大小: ${report.totalBytes} bytes (%.2f MB)".format(mb))
        appendLine("")
        for (bucket in report.buckets) {
            if (bucket.bytes <= 0L) continue
            appendLine(
                "  %-16s %10d bytes  (%5.1f%%)".format(bucket.label, bucket.bytes, report.percent(bucket))
            )
        }
        appendLine("")
        if (report.largestSo.isEmpty()) {
            appendLine("  原生库: 无 .so（WebRTC 已排除，运行时自服下载）")
        } else {
            appendLine("  最大的 .so：")
            for ((name, size) in report.largestSo) {
                appendLine("    %8d bytes  %s".format(size, name))
            }
        }
    }

    /** 主入口：命令行调用（Gradle 任务 `verifyReleaseSize` 包装此入口）。 */
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: "build/outputs/apk/release")
        val baselineBytes = args.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L } ?: DEFAULT_BASELINE_BYTES

        val apk = apkCandidates(dir).firstOrNull()
        if (apk == null) {
            System.err.println("SizeGuard: 未找到 Release APK（目录: ${dir.absolutePath}）")
            System.err.println("请先执行 :app:assembleRelease 或直接运行 :app:verifyReleaseSize")
            kotlin.system.exitProcess(2)
        }

        val report = analyzeApk(apk)
        println(formatReport(report))
        println(
            "基线   : $baselineBytes bytes (${baselineBytes / (1024.0 * 1024.0)} MB) " +
                "-> ${if (isWithinBaseline(report, baselineBytes)) "通过" else "超限"}"
        )
        if (!isWithinBaseline(report, baselineBytes)) {
            System.err.println("SizeGuard: Release APK 超过体积基线，阻断发版！")
            kotlin.system.exitProcess(1)
        }
        kotlin.system.exitProcess(0)
    }
}
