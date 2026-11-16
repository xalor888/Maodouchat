package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction

data class ReactionSummaryGroup(
    val emoji: String,
    val reactions: List<MessageReaction>,
    val reactedByMe: Boolean,
)

data class ReactionSummary(
    val visible: List<ReactionSummaryGroup>,
    val overflow: Int,
)

// 气泡下方的表情反应汇总：按 emoji 分组、组内按反应时间排序、组按最早反应排序，只露前 24 组。
object ReactionSummaryPolicy {

    const val MAX_VISIBLE_GROUPS = 24

    fun summarize(reactions: List<MessageReaction>, currentUserId: String?): ReactionSummary {
        val grouped = reactions.groupBy { it.emoji }
            .map { (emoji, group) ->
                ReactionSummaryGroup(
                    emoji = emoji,
                    reactions = group.sortedBy { it.reactedAt },
                    reactedByMe = currentUserId != null && group.any { it.userId == currentUserId },
                )
            }
            .sortedBy { it.reactions.firstOrNull()?.reactedAt ?: 0L }
        val visible = grouped.take(MAX_VISIBLE_GROUPS)
        return ReactionSummary(
            visible = visible,
            overflow = (grouped.size - visible.size).coerceAtLeast(0),
        )
    }
}
