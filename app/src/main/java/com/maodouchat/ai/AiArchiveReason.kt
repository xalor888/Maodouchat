package com.maodouchat.ai

import android.content.Context
import com.maodouchat.R
import com.maodouchat.domain.conversation.ArchiveSuggestionReason

/** 归档建议原因文案簇：种类判定走 :domain:conversation 的纯逻辑，这里只取本地化文案。 */
internal object AiArchiveReason {

    // 8.48：归档原因国际化（此前硬编码中文，英文用户看到中文文案）
    fun buildReason(context: Context, idleDays: Long, recent30: Int): String =
        when (ArchiveSuggestionReason.selectKind(idleDays, recent30)) {
            ArchiveSuggestionReason.Kind.Idle60 -> context.getString(R.string.ai_archive_reason_idle60, idleDays, recent30)
            ArchiveSuggestionReason.Kind.Idle21 -> context.getString(R.string.ai_archive_reason_idle21)
            ArchiveSuggestionReason.Kind.LowActivity -> context.getString(R.string.ai_archive_reason_low30)
            ArchiveSuggestionReason.Kind.Declining -> context.getString(R.string.ai_archive_reason_decline)
        }
}
