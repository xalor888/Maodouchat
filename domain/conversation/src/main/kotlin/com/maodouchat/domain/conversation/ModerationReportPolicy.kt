package com.maodouchat.domain.conversation

// 举报待处理判定（与原调用点一致忽略大小写）与目标可删内容判定；
// 后者复用 moderationReportTargetKind：MESSAGE/POST/COMMENT 之外一律不可删。
fun isModerationReportPending(status: String): Boolean =
    status.equals("PENDING", ignoreCase = true)

fun moderationReportTargetCanDeleteContent(targetType: String): Boolean =
    moderationReportTargetKind(targetType) in setOf(
        ModerationReportTargetKind.Message,
        ModerationReportTargetKind.Post,
        ModerationReportTargetKind.Comment,
    )
