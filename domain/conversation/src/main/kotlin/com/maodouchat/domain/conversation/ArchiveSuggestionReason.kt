package com.maodouchat.domain.conversation

/**
 * 归档建议原因种类：按静置天数与近 30 天消息数选一条原因（纯逻辑）。
 * 文案仍由调用方按本地化字符串解析，domain 只决定种类。
 */
object ArchiveSuggestionReason {

    enum class Kind {
        /** 静置 60 天及以上（文案带 idleDays 与 recent30 两个参数）。 */
        Idle60,

        /** 静置 21 天及以上。 */
        Idle21,

        /** 近 30 天消息不超过 2 条。 */
        LowActivity,

        /** 其余：活跃度下滑。 */
        Declining,
    }

    /**
     * 按静置天数与近 30 天消息数选原因种类；阈值逐字平移原
     * AiArchiveReason.buildReason 的 when 分支（含分支优先级）。
     */
    fun selectKind(idleDays: Long, recent30: Int): Kind =
        when {
            idleDays >= 60 -> Kind.Idle60
            idleDays >= 21 -> Kind.Idle21
            recent30 <= 2 -> Kind.LowActivity
            else -> Kind.Declining
        }
}
