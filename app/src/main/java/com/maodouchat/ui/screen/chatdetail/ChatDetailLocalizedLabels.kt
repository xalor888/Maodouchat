package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.core.model.AiFileAnalysisMode
import com.maodouchat.core.model.AiImageAnalysisMode
import com.maodouchat.core.model.AiSummaryScope
import com.maodouchat.domain.messaging.AiFileAnalysisModeKind
import com.maodouchat.domain.messaging.AiImageAnalysisModeKind
import com.maodouchat.domain.messaging.AiSummaryScopeKind
import com.maodouchat.domain.messaging.aiFileAnalysisModeKind
import com.maodouchat.domain.messaging.aiImageAnalysisModeKind
import com.maodouchat.domain.messaging.aiSummaryScopeKind

@Composable
internal fun AiSummaryScope.localizedLabel(): String = stringResource(when (aiSummaryScopeKind(this)) {
    AiSummaryScopeKind.RECENT -> R.string.chat_ai_summary_scope_recent
    AiSummaryScopeKind.TODAY -> R.string.chat_ai_summary_scope_today
    AiSummaryScopeKind.SEVEN_DAYS -> R.string.chat_ai_summary_scope_week
    AiSummaryScopeKind.THIRTY_DAYS -> R.string.chat_ai_summary_scope_month
    AiSummaryScopeKind.SEARCH_RESULTS -> R.string.chat_ai_summary_scope_search
    AiSummaryScopeKind.UNREAD -> R.string.chat_ai_summary_scope_unread
})

@Composable
internal fun AiImageAnalysisMode.localizedLabel(): String = stringResource(when (aiImageAnalysisModeKind(this)) {
    AiImageAnalysisModeKind.DESCRIBE -> R.string.chat_ai_image_mode_describe
    AiImageAnalysisModeKind.OCR -> R.string.chat_ai_image_mode_ocr
    AiImageAnalysisModeKind.SAFETY -> R.string.chat_ai_image_mode_safety
})

@Composable
internal fun AiFileAnalysisMode.localizedLabel(): String = stringResource(when (aiFileAnalysisModeKind(this)) {
    AiFileAnalysisModeKind.SUMMARIZE -> R.string.chat_ai_file_mode_summarize
    AiFileAnalysisModeKind.QUESTION -> R.string.chat_ai_file_mode_question
})
