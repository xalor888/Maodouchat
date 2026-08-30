package com.maodouchat.server.repository

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** B10：动态图片 URL 序列化共享助手（command 与 query 共用）。 */
internal object PostJson {
    val json = Json { ignoreUnknownKeys = true }
    val imageUrlListSerializer = ListSerializer(String.serializer())

    fun decodeImageUrls(value: String): List<String> = try {
        json.decodeFromString(imageUrlListSerializer, value)
    } catch (_: Exception) {
        emptyList()
    }
}
