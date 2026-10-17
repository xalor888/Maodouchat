package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.navigation.MainTab

/**
 * 会话列表的「临时静音 + 首次登录引导」弹层（G138 从 `ChatListScreen.kt` 拆出，原 65 行）。
 *
 * 两个语句：`silentUntilChat?.let { }`（1/8/24 小时临时静音，本地 per-chat）与
 * `if (showPostLoginGuide)`（首次登录引导：去添加好友 / 扫一扫 / 稍后再说）。
 *
 * 两个可变状态以「值 + setter」成对传入，不抓全局单例、不读数据库。
 * 纯搬移，不改判断。
 */
@Composable
internal fun ChatListMiscDialogs(
    silentUntilChat: Chat?,
    showPostLoginGuide: Boolean,
    onSilentUntilChatChange: (Chat?) -> Unit,
    onShowPostLoginGuideChange: (Boolean) -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenScan: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copyChatNameLabel = stringResource(R.string.chat_copy_chat_name)
    val copiedTip = stringResource(R.string.chat_copied)
    val copyChatIdLabel = stringResource(R.string.chat_copy_chat_id)
    val silentUntilSetTip = stringResource(R.string.chat_silent_until_set)

    // 1.31：会话列表「临时静音至」对话框（1/8/24 小时，本地 per-chat）
    silentUntilChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { onSilentUntilChatChange(null)},
            title = { Text(stringResource(R.string.chat_silent_until_title)) },
            text = {
                Column {
                    listOf(
                        (1L * 3600_000L) to R.string.chat_silent_until_1h,
                        (8L * 3600_000L) to R.string.chat_silent_until_8h,
                        (24L * 3600_000L) to R.string.chat_silent_until_24h
                    ).forEach { (ms, labelRes) ->
                        TextButton(
                            onClick = {
                                com.maodouchat.notification.ChatQuietHoursStore.setSilentUntil(
                                    context,
                                    chat.id,
                                    System.currentTimeMillis() + ms
                                )
                                android.widget.Toast.makeText(context, silentUntilSetTip, android.widget.Toast.LENGTH_SHORT).show()
                                onSilentUntilChatChange(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(labelRes), modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onSilentUntilChatChange(null)}) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 8.47：首次登录引导（去添加好友 / 扫一扫 / 稍后再说；任一动作后 markSeen 不再弹）
    if (showPostLoginGuide) {
        AlertDialog(
            onDismissRequest = {
                onShowPostLoginGuideChange(false)
                com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
            },
            title = { Text(stringResource(R.string.post_login_guide_title)) },
            text = { Text(stringResource(R.string.post_login_guide_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onShowPostLoginGuideChange(false)
                    com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
                    // 跳转通讯录 tab（MainTab.CONTACTS）
                    onNavigateToTab(MainTab.CONTACTS)
                }) { Text(stringResource(R.string.post_login_guide_add)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onShowPostLoginGuideChange(false)
                        com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
                        onOpenScan()
                    }) { Text(stringResource(R.string.post_login_guide_scan)) }
                    TextButton(onClick = {
                        onShowPostLoginGuideChange(false)
                        com.maodouchat.util.PostLoginGuidePreferences.markSeen(context)
                    }) { Text(stringResource(R.string.post_login_guide_later)) }
                }
            }
        )
    }}
