package com.maodouchat.ai

import android.content.Context
import com.maodouchat.R

/** 归档建议原因文案簇：按静置天数与近 30 天消息数选一条原因。 */
internal object AiArchiveReason {

    // 8.48：归档原因国际化（此前硬编码中文，英文用户看到中文文案）
    fun buildReason(context: Context, idleDays: Long, recent30: Int): String =
        when {
            idleDays >= 60 -> context.getString(R.string.ai_archive_reason_idle60, idleDays, recent30)
            idleDays >= 21 -> context.getString(R.string.ai_archive_reason_idle21)
            recent30 <= 2 -> context.getString(R.string.ai_archive_reason_low30)
            else -> context.getString(R.string.ai_archive_reason_decline)
        }
}
