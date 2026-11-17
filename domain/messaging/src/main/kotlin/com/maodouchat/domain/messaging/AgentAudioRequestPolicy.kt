package com.maodouchat.domain.messaging

// 语音转写请求装配：转写地址、默认音频 mime、上传文件名与两处错误文案。
// 自 app 侧 OpenAiCompatAudioClient.transcribeAudio 收敛，网络请求仍在 app 侧。
object AgentAudioRequestPolicy {

    const val FILE_NAME: String = "voice.m4a"

    const val DECODE_ERROR: String = "无法解码语音"

    const val EMPTY_TEXT_ERROR: String = "语音接口没有 text"

    fun transcriptionUrl(baseUrl: String): String =
        baseUrl.trimEnd('/') + "/audio/transcriptions"

    fun defaultMime(mimeType: String): String = mimeType.ifBlank { "audio/mp4" }
}
