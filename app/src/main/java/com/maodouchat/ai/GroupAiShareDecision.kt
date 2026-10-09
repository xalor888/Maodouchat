package com.maodouchat.ai

import com.maodouchat.ai.GroupAiSharePolicy.BlockReason
import com.maodouchat.ai.GroupAiSharePolicy.ShareDecision

// 分享决策：答案私密直到用户点确认；已分享过的不重复发。原逻辑逐字搬入。
internal object GroupAiShareDecision {
    const val MAX_SHARE_CHARS = 4_000

    fun decideShare(
        isGroup: Boolean,
        answer: String?,
        alreadyShared: Boolean = false
    ): ShareDecision {
        if (!isGroup) return ShareDecision(false, reason = BlockReason.NOT_GROUP)
        if (alreadyShared) return ShareDecision(false, reason = BlockReason.ALREADY_SHARED)
        val body = answer?.trim().orEmpty()
        if (body.isEmpty()) return ShareDecision(false, reason = BlockReason.EMPTY_ANSWER)
        return ShareDecision(true, body = body.take(MAX_SHARE_CHARS))
    }
}
