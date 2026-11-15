package com.maodouchat.domain.groups

// 群 PK 创建校验：两侧标题均先 trim 再判空与 120 字，不依赖 Android。
// 校验式逐字取自原 GroupPollPolicy.isValidPkTitle 与 GroupPkScreen 内联判定。
object GroupPkValidationPolicy {

    fun isValidTitle(title: String): Boolean =
        title.isNotBlank() && title.trim().length <= MAX_TITLE_LENGTH

    fun isValidTitles(leftTitle: String, rightTitle: String): Boolean =
        isValidTitle(leftTitle) && isValidTitle(rightTitle)

    private const val MAX_TITLE_LENGTH = 120
}
