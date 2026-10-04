package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class ChainCreateFields(
    val title: String,
    val topic: String,
    val maxEntries: Int,
)

internal data class PkCreateFields(
    val leftTitle: String,
    val rightTitle: String,
)

// 接龙创建三字段：maxEntries 缺省/非法一律回 200；空串与长度校验仍在路由层。
internal fun parseChainCreateFields(obj: JsonObject): ChainCreateFields {
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
    val topic = obj["topic"]?.jsonPrimitive?.content.orEmpty()
    val maxEntries = obj["maxEntries"]?.jsonPrimitive?.intOrNull ?: 200
    return ChainCreateFields(title, topic, maxEntries)
}

internal fun parseChainJoinContent(obj: JsonObject): String =
    obj["content"]?.jsonPrimitive?.content.orEmpty()

internal fun parsePkCreateFields(obj: JsonObject): PkCreateFields {
    val leftTitle = obj["leftTitle"]?.jsonPrimitive?.content.orEmpty()
    val rightTitle = obj["rightTitle"]?.jsonPrimitive?.content.orEmpty()
    return PkCreateFields(leftTitle, rightTitle)
}

internal fun parsePkVoteChoice(obj: JsonObject): String =
    obj["choice"]?.jsonPrimitive?.content.orEmpty()
