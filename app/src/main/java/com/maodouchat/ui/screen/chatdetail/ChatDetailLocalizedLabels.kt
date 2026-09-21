package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.MessageMeta

/**
 * 会话详情页的本地化标签（G184 从 ChatDetailRoute.kt 抽出的 4 个顶层声明）。
 *
 * 抽出的理由：`ChatDetailRoute.kt` 3667 行且已顶到在监上限，
 * 而这四个声明与路由本身无耦合——一个是纯函数，三个是
 * `@Composable` 的枚举→字符串资源映射。搬过来即可，无需改任何调用点
 * （同包，顶层声明的可见性不变）。
 */

/** 展示用译文：优先用户选的语言，回退中文，再回退最后一个非空译文。 */

internal fun MessageMeta.displayedTranslation(): String? {
    val preferred = preferredTranslationLanguage?.let(translations::get)
    return preferred?.takeIf { it.isNotBlank() }
        ?: translations["中文"]?.takeIf { it.isNotBlank() }
        ?: translations.values.lastOrNull { it.isNotBlank() }
}

@Composable
internal fun AiSummaryScope.localizedLabel(): String = stringResource(when (this) {
    AiSummaryScope.RECENT -> R.string.chat_ai_summary_scope_recent
    AiSummaryScope.TODAY -> R.string.chat_ai_summary_scope_today
    AiSummaryScope.SEVEN_DAYS -> R.string.chat_ai_summary_scope_week
    AiSummaryScope.THIRTY_DAYS -> R.string.chat_ai_summary_scope_month
    AiSummaryScope.SEARCH_RESULTS -> R.string.chat_ai_summary_scope_search
    AiSummaryScope.UNREAD -> R.string.chat_ai_summary_scope_unread
})

@Composable
internal fun AiImageAnalysisMode.localizedLabel(): String = stringResource(when (this) {
    AiImageAnalysisMode.DESCRIBE -> R.string.chat_ai_image_mode_describe
    AiImageAnalysisMode.OCR -> R.string.chat_ai_image_mode_ocr
    AiImageAnalysisMode.SAFETY -> R.string.chat_ai_image_mode_safety
})

@Composable
internal fun AiFileAnalysisMode.localizedLabel(): String = stringResource(when (this) {
    AiFileAnalysisMode.SUMMARIZE -> R.string.chat_ai_file_mode_summarize
    AiFileAnalysisMode.QUESTION -> R.string.chat_ai_file_mode_question
})
