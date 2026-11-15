package com.maodouchat.domain.groups

// 群接龙创建与参与校验：标题/话题/条目长度上限与已参与、已关闭时的参与准入，不依赖 Android。
// 校验式逐字取自原 GroupPollPolicy 与 GroupChainScreen 内联判定：标题先 trim 再判空与 200 字，
// 话题可空只限 500 字，条目内容先 trim 再判空与 500 字。
object GroupChainValidationPolicy {

    fun isValidTitle(title: String): Boolean =
        title.isNotBlank() && title.trim().length <= MAX_TITLE_LENGTH

    fun isValidTopic(topic: String): Boolean = topic.length <= MAX_TOPIC_LENGTH

    fun isValidEntryContent(content: String): Boolean =
        content.isNotBlank() && content.trim().length <= MAX_ENTRY_LENGTH

    // 已参与或接龙已关闭时不再允许提交条目。
    fun canJoin(myJoined: Boolean, active: Boolean): Boolean = !myJoined && active

    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_TOPIC_LENGTH = 500
    private const val MAX_ENTRY_LENGTH = 500
}
