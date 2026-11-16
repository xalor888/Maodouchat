package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ConversationWidgetContract
import com.maodouchat.core.model.WidgetConfig
import org.json.JSONArray
import org.json.JSONObject

// 小组件配置 JSON 编解码：键名 chatIds/badge/compact 与缺省值（badge 默认 true、
// compact 默认 false）须与已存配置逐字节一致；损坏 JSON 解码回默认配置。
object WidgetConfigJsonPolicy {

    fun encodeConfig(config: WidgetConfig): String =
        JSONObject()
            .put("chatIds", JSONArray(config.chatIds))
            .put("badge", config.showUnreadBadge)
            .put("compact", config.compact)
            .toString()

    fun decodeConfig(json: String): WidgetConfig = runCatching {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("chatIds")
        val ids = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        WidgetConfig(
            chatIds = ids.take(ConversationWidgetContract.MAX_CONFIG_CHATS),
            showUnreadBadge = obj.optBoolean("badge", true),
            compact = obj.optBoolean("compact", false),
        )
    }.getOrDefault(WidgetConfig())
}
