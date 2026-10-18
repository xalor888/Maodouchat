package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable

/** 下半区弹窗簇：AI 图像/文件分析与文件问答弹窗，原在 ChatDetailLowerDialogHost 内联，纯搬移。 */
@Composable
internal fun ChatDetailAiAnalysisDialogs(
    messageActions: ChatDetailMessageActionState,
    viewModel: ChatDetailViewModel,
    drafts: ChatDetailDraftState,
) {
    messageActions.messageToAnalyzeImage?.let { message ->
        AiImageAnalysisModeDialog(
            onSelect = { mode ->
                messageActions.messageToAnalyzeImage = null
                viewModel.requestAiImageAnalysis(message.id, mode)
            },
            onDismiss = { messageActions.messageToAnalyzeImage = null }
        )
    }

    messageActions.messageToAnalyzeFile?.let { message ->
        AiFileAnalysisModeDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            onSelect = { mode ->
                messageActions.messageToAnalyzeFile = null
                if (mode == AiFileAnalysisMode.SUMMARIZE) {
                    viewModel.requestAiFileAnalysis(message.id, mode)
                } else {
                    drafts.fileQuestionDraft = ""
                    messageActions.fileQuestionMessage = message
                }
            },
            onDismiss = { messageActions.messageToAnalyzeFile = null }
        )
    }

    messageActions.fileQuestionMessage?.let { message ->
        AiFileQuestionDialog(
            fileName = message.parsedMeta().fileName.orEmpty(),
            question = drafts.fileQuestionDraft,
            onQuestionChange = { drafts.fileQuestionDraft = it.take(500) },
            onSubmit = {
                viewModel.requestAiFileAnalysis(message.id, AiFileAnalysisMode.QUESTION, drafts.fileQuestionDraft)
                messageActions.fileQuestionMessage = null
                drafts.fileQuestionDraft = ""
            },
            onDismiss = {
                messageActions.fileQuestionMessage = null
                drafts.fileQuestionDraft = ""
            }
        )
    }
}
