package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.LocalAiCompletion

// 本机模型单轮调用的结果规整，自 app 侧 AgentTextCompletion 与 LocalAiFileTasks 收敛；
// 模型调用本身仍留在 app 侧。
object AgentCompletionResultPolicy {

    fun toResult(result: LocalAiCompletion): Result<String> = when (result) {
        is LocalAiCompletion.Text -> Result.success(AgentCompletionTextPolicy.trimResult(result.content))
        is LocalAiCompletion.Tools -> Result.success(AgentCompletionTextPolicy.trimResult(result.content))
        is LocalAiCompletion.Error -> Result.failure(IllegalStateException(result.message))
    }

    fun blankTextError(): String = "Error: text required"

    fun rewriteFailedFallback(): String = "rewrite failed"
}
