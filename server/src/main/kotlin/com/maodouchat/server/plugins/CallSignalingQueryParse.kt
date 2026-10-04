package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 不 trim 的 orEmpty：下游自己做 blank/格式判定（附件 chatId/messageId、信令 callId 都要原样透出）。
internal fun parseRawOrEmpty(params: Parameters, name: String): String =
    params[name].orEmpty()

// 严格布尔开关（信令 pending 的 offersOnly）：只有字面量 "true" 算开，其余一律关。
internal fun parseStrictBooleanFlag(params: Parameters, name: String): Boolean =
    params[name]?.toBooleanStrictOrNull() == true
