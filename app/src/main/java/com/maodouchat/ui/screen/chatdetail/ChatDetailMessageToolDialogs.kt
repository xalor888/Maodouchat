package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R

/** 下半区弹窗簇：稍后提醒/翻译语言/举报/日期跳转弹窗，原在 ChatDetailLowerDialogHost 内联，纯搬移。 */
@Composable
internal fun ChatDetailMessageToolDialogs(
    messageActions: ChatDetailMessageActionState,
    drafts: ChatDetailDraftState,
    viewModel: ChatDetailViewModel,
) {
    // 8.41：消息「稍后提醒」时间选择
    messageActions.messageToRemind?.let { message ->
        MessageReminderTimeDialog(
            onPick = { delayMs ->
                messageActions.messageToRemind = null
                viewModel.scheduleMessageReminder(message, System.currentTimeMillis() + delayMs)
            },
            onDismiss = { messageActions.messageToRemind = null }
        )
    }

    messageActions.messageToTranslate?.let { message ->
        TranslationLanguageDialog(
            translatedLanguages = message.parsedMeta().translations.keys,
            onDismiss = { messageActions.messageToTranslate = null },
            onSelect = { language ->
                viewModel.requestMessageTranslation(message.id, language)
                messageActions.messageToTranslate = null
            }
        )
    }

    messageActions.messageToReport?.let { msg ->
        ReportDialog(
            title = stringResource(R.string.chat_report_message),
            onDismiss = { messageActions.messageToReport = null },
            onReport = { reason, description ->
                viewModel.reportMessage(msg.id, reason, description)
                messageActions.messageToReport = null
            }
        )
    }

    if (drafts.showDateJumpDialog) {
        DateJumpDialog(
            onDismiss = { drafts.showDateJumpDialog = false },
            onJump = { dayStartMillis ->
                drafts.showDateJumpDialog = false
                viewModel.jumpToDate(dayStartMillis)
            }
        )
    }
}
