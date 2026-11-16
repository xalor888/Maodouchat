package com.maodouchat.domain.conversation

// 审核举报/规则文案→种类；R.string 映射留在 app 侧，未知 wire 值原样回显。
enum class ModerationReportStatusKind {
    Open,
    InReview,
    Resolved,
    Rejected,
    All,
    Raw,
}

fun moderationReportStatusKind(status: String): ModerationReportStatusKind = when (status) {
    "OPEN" -> ModerationReportStatusKind.Open
    "IN_REVIEW" -> ModerationReportStatusKind.InReview
    "RESOLVED" -> ModerationReportStatusKind.Resolved
    "REJECTED" -> ModerationReportStatusKind.Rejected
    "ALL" -> ModerationReportStatusKind.All
    else -> ModerationReportStatusKind.Raw
}

enum class ModerationReportActionKind {
    DeleteContent,
    NoAction,
    RestrictMessages24h,
    RestrictPosts7d,
    Suspend24h,
    Raw,
}

fun moderationReportActionKind(action: String): ModerationReportActionKind = when (action) {
    "DELETE_CONTENT" -> ModerationReportActionKind.DeleteContent
    "NO_ACTION" -> ModerationReportActionKind.NoAction
    "RESTRICT_MESSAGES_24H" -> ModerationReportActionKind.RestrictMessages24h
    "RESTRICT_POSTS_7D" -> ModerationReportActionKind.RestrictPosts7d
    "SUSPEND_24H" -> ModerationReportActionKind.Suspend24h
    else -> ModerationReportActionKind.Raw
}

enum class ModerationRiskActionKind {
    WarnMod,
    AutoHold,
    AutoDelete,
    AutoRateLimit,
    Observed,
    Raw,
}

fun moderationRiskActionKind(action: String): ModerationRiskActionKind = when (action) {
    "WARN_MOD" -> ModerationRiskActionKind.WarnMod
    "AUTO_HOLD" -> ModerationRiskActionKind.AutoHold
    "AUTO_DELETE" -> ModerationRiskActionKind.AutoDelete
    "AUTO_RATE_LIMIT" -> ModerationRiskActionKind.AutoRateLimit
    "OBSERVED" -> ModerationRiskActionKind.Observed
    else -> ModerationRiskActionKind.Raw
}

enum class ModerationReportTargetKind {
    User,
    Message,
    MessageMeta,
    Post,
    Comment,
    All,
    Raw,
}

fun moderationReportTargetKind(type: String): ModerationReportTargetKind = when (type) {
    "USER" -> ModerationReportTargetKind.User
    "MESSAGE" -> ModerationReportTargetKind.Message
    "MESSAGE_META" -> ModerationReportTargetKind.MessageMeta
    "POST" -> ModerationReportTargetKind.Post
    "COMMENT" -> ModerationReportTargetKind.Comment
    "ALL" -> ModerationReportTargetKind.All
    else -> ModerationReportTargetKind.Raw
}
