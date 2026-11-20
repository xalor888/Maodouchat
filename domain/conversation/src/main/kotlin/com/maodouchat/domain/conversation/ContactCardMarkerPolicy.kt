package com.maodouchat.domain.conversation

// 名片标记 `[contactUser:<userId>]` 的处理：格式由 ChatContactCardController /
// SpecialAttachmentMessagePolicy 生产；此处收敛提取、存在判定与剥离。
object ContactCardMarkerPolicy {

    private const val MARKER_PREFIX = "[contactUser:"

    private val USER_ID_RE = Regex("\\[contactUser:([^\\]]+)")

    // 首个标记内的 userId；无标记或 id 为空返回 null。
    fun extractUserId(text: String): String? =
        USER_ID_RE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // 文本是否含名片标记（复制菜单的显示条件，与原字面量 contains 逐字一致）。
    fun containsMarker(text: String): Boolean = text.contains(MARKER_PREFIX)

    /** 1.18：剥离名片标记行 `[contactUser:xxx]`（含前一换行），会话列表预览等不显示裸标记。 */
    private val CONTACT_CARD_MARKER_RE = Regex("\\n?\\[contactUser:[^\\]]+\\]")
    fun stripContactCardMarker(text: String): String = CONTACT_CARD_MARKER_RE.replace(text, "")
}
