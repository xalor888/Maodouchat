@file:Suppress("DEPRECATION")

package com.maodouchat.ui.screen.contacts

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.contacts.QrScanFeedbackPolicy
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.util.QrCodeGenerator
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫一扫页的结果弹窗簇：从 ScanScreen 拆出的纯 UI 文件。
 * 读写全部走 [ScanScreenUiState]，与原先内联在 ScanScreen 尾部的弹窗逻辑逐一对应。
 */
@Composable
internal fun ScanResultDialogs(state: ScanScreenUiState) {
    if (state.invalidQr) {
        AlertDialog(
            onDismissRequest = { state.invalidQr = false },
            title = { Text(stringResource(R.string.contacts_invalid_qr_title)) },
            text = { Text(stringResource(R.string.contacts_invalid_qr_message)) },
            confirmButton = {
                TextButton(onClick = { state.invalidQr = false }) { Text(stringResource(R.string.chat_acknowledge)) }
            }
        )
    }

    // 扫描结果弹窗：用户资料 / 加载中 / 查不到用户
    val user = state.scannedUser
    if (state.scannedTarget is QrCodeGenerator.QrTarget.User && user != null) {
        val context = state.context
        val alreadyFriend = remember(user.id) {
            com.maodouchat.data.repository.FriendCacheStore.getFriendIds(context).contains(user.id)
        }
        AlertDialog(
            onDismissRequest = { state.scannedTarget = null; state.scannedUser = null; state.scannedUserFriendMessage = null },
            title = { Text(stringResource(R.string.contacts_found)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.LG, isOnline = user.isOnline)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(user.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (user.status.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(user.status, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(user.id, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    state.scannedUserFriendMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        val ok = msg == stringResource(R.string.contacts_friend_request_sent)
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (!alreadyFriend) {
                        TextButton(
                            enabled = !state.scannedUserFriendBusy,
                            onClick = {
                                if (!RuntimeFlags.isEnabled(context, RuntimeFlags.FRIEND_REQUESTS)) {
                                    state.scannedUserFriendMessage = context.getString(R.string.friend_requests_disabled)
                                    return@TextButton
                                }
                                val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
                                if (!com.maodouchat.session.CurrentSession.hasSession()) {
                                    state.scannedUserFriendMessage = state.qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                                    return@TextButton
                                }
                                state.scannedUserFriendBusy = true
                                state.scannedUserFriendMessage = null
                                state.scope.launch {
                                    try {
                                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                            expectedUserId = ownerUserId,
                                        )
                                        ) {
                                            state.scannedUserFriendMessage = state.qrScanMessage(context, QrScanFeedbackPolicy.forSessionExpired())
                                            return@launch
                                        }
                                        com.maodouchat.data.repository.ContactNetworkRepository().sendFriendRequest(userId = user.id).fold(
                                            onSuccess = {
                                                state.scannedUserFriendMessage = context.getString(R.string.contacts_friend_request_sent)
                                            },
                                            onFailure = { error ->
                                                state.scannedUserFriendMessage = error.message
                                                    ?: context.getString(R.string.contacts_friend_request_failed)
                                            }
                                        )
                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        state.scannedUserFriendMessage = error.message
                                            ?: context.getString(R.string.contacts_friend_request_failed)
                                    } finally {
                                        state.scannedUserFriendBusy = false
                                    }
                                }
                            }
                        ) { Text(stringResource(R.string.contacts_add_friend), color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = {
                        state.scannedTarget = null
                        state.onAddContact(user)
                    }) { Text(stringResource(R.string.contacts_start_chat), color = MaterialTheme.colorScheme.primary) }
                }
            },
            dismissButton = {
                TextButton(onClick = { state.scannedTarget = null; state.scannedUser = null; state.scannedUserFriendMessage = null }) {
                    Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                }
            }
        )
    } else if (state.scannedTarget is QrCodeGenerator.QrTarget.User && state.loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.contacts_parsing)) },
            text = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {}
        )
    } else if (state.scannedTarget is QrCodeGenerator.QrTarget.User) {
        // 查不到用户（或网络失败且无本地缓存）
        AlertDialog(
            onDismissRequest = { state.scannedTarget = null },
            title = { Text(stringResource(R.string.contacts_user_not_found)) },
            text = {
                Text(
                    state.scannedUserError?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.contacts_user_not_found_hint)
                )
            },
            confirmButton = { TextButton(onClick = { state.scannedTarget = null }) { Text(stringResource(R.string.chat_acknowledge)) } }
        )
    }

    // 安全码核验结果弹窗：已核验 / 核验中
    if (state.safetyScanResult != null) {
        val result = state.safetyScanResult!!
        AlertDialog(
            onDismissRequest = { state.safetyScanResult = null; state.scannedTarget = null },
            title = { Text(if (result.matched) stringResource(R.string.contacts_safety_verified) else stringResource(R.string.contacts_safety_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.safetyScanMessage(result))
                    Text(
                        stringResource(R.string.contacts_safety_peer_device, result.target.ownerUserId, result.target.ownerDeviceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            },
            confirmButton = {
                if (result.matched) {
                    TextButton(onClick = {
                        val ownerUserId = result.target.ownerUserId
                        val ownerDeviceId = result.target.ownerDeviceId
                        // markIdentityVerified 内部调用阻塞式 Room 查询，必须在 IO 线程执行，
                        // 避免主线程磁盘 I/O 导致 UI 卡顿/ANR。
                        state.scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                com.maodouchat.security.SignalIdentityAccess.markIdentityVerified(ownerUserId, ownerDeviceId)
                            }
                            Toast.makeText(
                                state.context,
                                if (ok) state.safetyTrustedMsg else state.safetyTrustFailedMsg,
                                Toast.LENGTH_SHORT
                            ).show()
                            state.safetyScanResult = null
                            state.scannedTarget = null
                        }
                    }) { Text(stringResource(R.string.contacts_safety_mark_trusted), color = MaterialTheme.colorScheme.primary) }
                } else {
                    TextButton(onClick = { state.safetyScanResult = null; state.scannedTarget = null }) { Text(stringResource(R.string.chat_acknowledge)) }
                }
            },
            dismissButton = {
                if (result.matched) {
                    TextButton(onClick = { state.safetyScanResult = null; state.scannedTarget = null }) { Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary) }
                }
            }
        )
    } else if (state.scannedTarget is QrCodeGenerator.QrTarget.Safety && state.loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.contacts_safety_verifying)) },
            text = { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {}
        )
    }
}
