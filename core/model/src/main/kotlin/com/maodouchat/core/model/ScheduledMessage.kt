package com.maodouchat.core.model

data class ScheduledMessage(
    val id: String,
    val chatId: String,
    val peerUserId: String,
    val text: String,
    val sendAtMillis: Long,
    val createdAtMillis: Long,
    val isGroup: Boolean = false,
    val ownerUserId: String = "",
    /** 1.07：重复发送间隔毫秒（0=一次性；如每日/每周提醒）。 */
    val repeatIntervalMs: Long = 0L,
    /** 1.21：重复总次数（0=不限，按已发次数 occurrencesSent 推进，达上限停止重排）。 */
    val repeatCount: Int = 0,
    /** 1.21：本链已发送次数（每次重排 +1，用于判定是否继续）。 */
    val occurrencesSent: Int = 0,
    /** 1.62：仅工作日重复（周一至周五，跳过周末）。 */
    val weekdaysOnly: Boolean = false,
    val status: String = "PENDING",
    val attempt: Int = 0,
    val timeZoneId: String = "",
    val idempotencyKey: String = "",
)
