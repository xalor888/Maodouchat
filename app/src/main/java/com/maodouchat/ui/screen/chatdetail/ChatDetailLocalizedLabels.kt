package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.core.model.AiSummaryScope

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
