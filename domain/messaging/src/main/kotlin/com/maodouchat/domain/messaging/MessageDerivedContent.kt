package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageMetaJson
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.json.Json

// Message 内容派生：把 meta 编回正文、从正文解析位置载荷。
// 由 :core:model 的 Message 成员函数迁入；parsedContent / parsedMeta 留守 core。
fun Message.withEncodedMeta(updatedMeta: MessageMeta): Message {
    val encoded = MessageMetaJson.encodeMessageMeta(updatedMeta)
    return copy(
        content = parsedContent() + Message.META_TAG_PREFIX + encoded + "</meta>",
        meta = updatedMeta
    )
}

fun Message.parsedLocation(): LocationPayload? {
    if (type != MessageType.LOCATION) return null
    return runCatching {
        LOCATION_JSON.decodeFromString(LocationPayload.serializer(), parsedContent())
    }.getOrNull()?.takeIf {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }
}

private val LOCATION_JSON = Json { ignoreUnknownKeys = true }
