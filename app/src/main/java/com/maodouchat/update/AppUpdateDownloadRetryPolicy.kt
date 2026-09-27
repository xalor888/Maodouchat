package com.maodouchat.update

/**
 * Pure retry classification for [AppUpdateDownloadWorker].
 *
 * 判据是 `error.message` 的文本（门序见 `OfficialApkInstaller.downloadAndVerify`）：
 * - 明确的可重试类：HTTP 非 2xx、空 body、过小、超时、DNS、连接失败；
 * - **G340 补充的瞬态 socket 类**：`Connection reset` / `Software caused connection abort`
 *   / `Broken pipe` / `unexpected end of stream`——这些是传输中途被打断的经典瞬态错误
 *   （中间盒 RST、服务端重启、截断下载），旧分类漏掉它们时更新会**静默放弃**。
 *   实测来源：`AppUpdateDownloadWorkerInstrumentedTest#unreachableOfficialHostIsClassifiedRetryable`
 *   在 AVD 上对官方域名不可达端口得到的正是 `Connection reset`（旧分类 → `Result.failure`）。
 * - 明确不可重试：完整性/来源类失败（sha 不匹配、签名/版本不符、非官方 URL）——重试只会
 *   再被拒，属于「等下一个版本」而不是「网络抖了」。
 *
 * 有界：Worker 侧 `runAttemptCount < 3` 才 retry，所以放宽瞬态类不会造成无限重试。
 */
object AppUpdateDownloadRetryPolicy {
    /**
     * 类型优先：`IOException` 一族（DNS 失败、连接被拒/RST、超时、截断）在本路径里
     * 天然是瞬态的——端口探测实测同一种失败会给出不同 message（`Connection reset` /
     * `connection closed`），靠字符串逐个枚举是**列不全**的；类型判定才是稳的那层。
     * 非 IO 异常（冲突/校验/协议类）回落到 [isRetryable] 的文本判据。
     */
    fun isRetryable(error: Throwable): Boolean =
        error is java.io.IOException || isRetryable(error.message.orEmpty())

    fun isRetryable(code: String): Boolean =
        code.startsWith("http_") ||
            code == "empty_body" ||
            code == "apk_too_small" ||
            code.contains("timeout", ignoreCase = true) ||
            code.contains("Unable to resolve", ignoreCase = true) ||
            code.contains("failed to connect", ignoreCase = true) ||
            code.contains("connection reset", ignoreCase = true) ||
            code.contains("connection abort", ignoreCase = true) ||
            code.contains("broken pipe", ignoreCase = true) ||
            code.contains("unexpected end of stream", ignoreCase = true)
}
