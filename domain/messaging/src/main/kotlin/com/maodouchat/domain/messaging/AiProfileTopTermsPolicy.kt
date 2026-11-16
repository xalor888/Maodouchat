package com.maodouchat.domain.messaging

// 会话画像 Top 8 热词：复制文本、画像弹窗、叙事提示词三处共用同一条截断规则。
object AiProfileTopTermsPolicy {

    const val MAX_TOP_TERMS: Int = 8

    fun displayTerms(terms: List<String>, separator: String): String =
        terms.take(MAX_TOP_TERMS).joinToString(separator)
}
