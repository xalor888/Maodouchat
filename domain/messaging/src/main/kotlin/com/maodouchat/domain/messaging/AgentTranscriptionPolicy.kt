package com.maodouchat.domain.messaging

import org.json.JSONObject

// 语音转写响应解析，自 app 侧 OpenAiCompatAudioClient 收敛；
// 网络请求仍留在 app 侧。
object AgentTranscriptionPolicy {

    fun extractText(payload: String): String =
        runCatching { JSONObject(payload).optString("text") }.getOrNull().orEmpty()
}
