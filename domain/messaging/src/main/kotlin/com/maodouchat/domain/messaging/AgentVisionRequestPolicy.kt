package com.maodouchat.domain.messaging

import org.json.JSONArray
import org.json.JSONObject

// 视觉理解请求体装配，自 app 侧 OpenAiCompatVisionClient 收敛；
// 网络请求仍留在 app 侧。
object AgentVisionRequestPolicy {

    fun visionUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/chat/completions"

    fun buildVisionBody(
        model: String,
        instruction: String,
        images: List<Pair<String, String>>,
        maxImages: Int,
    ): JSONObject {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", instruction.take(2_000)))
        images.take(maxImages).forEach { (mimeType, imageBase64) ->
            val mime = mimeType.ifBlank { "image/jpeg" }
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:$mime;base64,$imageBase64"))
            )
        }
        val user = JSONObject().put("role", "user").put("content", parts)
        return JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "只根据图片作答。不要声称已发送或已删除消息。"))
                    .put(user)
            )
    }
}
