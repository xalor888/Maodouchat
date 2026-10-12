package com.maodouchat.server.repository

import com.maodouchat.server.model.ReportResponse

class ReportWorkflow

sealed class CreateResult {
    data class Success(val report: ReportResponse) : CreateResult()
    data class Failure(val message: String) : CreateResult()
}

sealed class UpdateResult {
    data class Success(val report: ReportResponse) : UpdateResult()
    data class Failure(val message: String) : UpdateResult()
}

/**
 * 处置标记结果：
 * - Applied：本请求首次写入 actionTaken，调用方应执行副作用
 * - AlreadyDone：已处置，调用方必须跳过副作用
 */
sealed class ActionMarkResult {
    data class Applied(val report: ReportResponse) : ActionMarkResult()
    data class AlreadyDone(val report: ReportResponse) : ActionMarkResult()
    data class Failure(val message: String) : ActionMarkResult()
}

/**
 * Action disposition result. [businessAction] is executed while the report
 * row lock is held; an action is only recorded after it returns true.
 */
sealed class ExecuteActionResult {
    data class Completed(val report: ReportResponse) : ExecuteActionResult()
    data class AlreadyDone(val report: ReportResponse) : ExecuteActionResult()
    data class BusinessActionFailed(val report: ReportResponse) : ExecuteActionResult()
    data class Failure(val message: String) : ExecuteActionResult()
}

internal val ALLOWED_TARGET_TYPES = setOf("USER", "MESSAGE", "POST", "COMMENT")
internal val ALLOWED_STATUSES = setOf("OPEN", "IN_REVIEW", "RESOLVED", "REJECTED")
internal val ALLOWED_ACTIONS = setOf("DELETE_CONTENT", "NO_ACTION", "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H")
internal val FINAL_STATUSES = setOf("RESOLVED", "REJECTED")
internal const val MAX_REASON_LENGTH = 80
internal const val MAX_DESCRIPTION_LENGTH = 800
internal const val MAX_RESOLUTION_NOTE_LENGTH = 800
