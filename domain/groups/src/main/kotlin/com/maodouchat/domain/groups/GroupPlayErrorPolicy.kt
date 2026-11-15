package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupPlayJson

// 群玩法服务端错误文案提取：仓库层异常 message 常带 "HTTP 500: " 这类前缀，
// 先取第一个冒号后的正文再按 `{error}` / `{message}` 解析，与原投票两处内联写法逐字一致。
object GroupPlayErrorPolicy {

    fun serverErrorText(failureMessage: String): String? =
        GroupPlayJson.errorMessage(
            failureMessage.substringAfter(':', missingDelimiterValue = failureMessage)
        )
}
