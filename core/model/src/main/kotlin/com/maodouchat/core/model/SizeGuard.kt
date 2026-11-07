package com.maodouchat.core.model

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 构建期体积统计辅助（B1 包体瘦身基线）：纯 JVM，读 Release APK 分解 dex/.so/资源占比。
 * 由 Gradle 任务 `:app:verifyReleaseSize` 包装调用：`SizeGuard <apk 目录> [基线字节数]`，
 * 超过基线打印分解表并以非零退出码阻断发版。
 */
object SizeGuard {

    /** 默认基线：10MB（feature-vision B1 目标 APK ≤ 10MB）。 */
    const val DEFAULT_BASELINE_BYTES: Long = 10L * 1024L * 1024L

    /** 优先候选的 APK 文件名（按优先级）。 */
    private val PREFERRED_APK_NAMES = listOf(
        "app-release.apk",
        "app-release-unsigned.apk",
    )

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
