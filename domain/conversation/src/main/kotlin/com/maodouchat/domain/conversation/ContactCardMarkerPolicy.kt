package com.maodouchat.domain.conversation

// 名片标记 `[contactUser:<userId>]` 的解析侧：格式由 ChatContactCardController /
// SpecialAttachmentMessagePolicy 生产，剥离已在 ChatMarkdown；此处只收敛 app 侧
// 残留的提取与存在判定。
object ContactCardMarkerPolicy {

    private const val MARKER_PREFIX = "[contactUser:"

    private val USER_ID_RE = Regex("\\[contactUser:([^\\]]+)")

    // 首个标记内的 userId；无标记或 id 为空返回 null。
    fun extractUserId(text: String): String? =
        USER_ID_RE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // 文本是否含名片标记（复制菜单的显示条件，与原字面量 contains 逐字一致）。
    fun containsMarker(text: String): Boolean = text.contains(MARKER_PREFIX)
}
