package com.maodouchat.ui.screen.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.group.JoinGroupInviteResult
import com.maodouchat.group.JoinGroupInviteUseCase
import com.maodouchat.data.repository.ContactNetworkRepository
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * P08：群邀请落地页（深链 / QR / 系统入口统一）。
 * 只提交 inviteCode，观察用例结果后导航；不在 Composable 内直写业务分支外的 API 细节。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupInviteScreen(
    inviteCode: String,
    onBack: () -> Unit,
    onJoined: (chatId: String) -> Unit,
) {
    val context = LocalContext.current
    // 在 Composable 顶层读取，而不是在 LaunchedEffect 内 context.getString(...)：
    // 后者会被 lint 判为 LocalContextGetResourceValueCall（配置变更时不会失效）。
    val invalidCodeMessage = stringResource(R.string.contacts_invite_invalid_or_expired)
    var loading by remember(inviteCode) { mutableStateOf(true) }
    var errorMessage by remember(inviteCode) { mutableStateOf<String?>(null) }
    var joinedChatId by remember(inviteCode) { mutableStateOf<String?>(null) }
    var joinedTitle by remember(inviteCode) { mutableStateOf<String?>(null) }

    LaunchedEffect(inviteCode) {
        loading = true
        errorMessage = null
        joinedChatId = null
        joinedTitle = null
        val tokenManager = TokenManager.getInstance(context)
        val ownerUserId = tokenManager.getUserId().orEmpty()
        val useCase = JoinGroupInviteUseCase(
            tokenProvider = { tokenManager.getToken().orEmpty() },
            userIdProvider = { tokenManager.getUserId().orEmpty() },
            sessionGate = {
                BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            },
            join = { auth, invite -> ContactNetworkRepository().joinGroupByInvite(auth, invite) },
        )
        when (val result = useCase.join(inviteCode)) {
            is JoinGroupInviteResult.Joined -> {
                joinedChatId = result.chat.id
                joinedTitle = result.chat.groupName?.takeIf { it.isNotBlank() }
                loading = false
            }
            JoinGroupInviteResult.InvalidCode -> {
                errorMessage = invalidCodeMessage
                loading = false
            }
            is JoinGroupInviteResult.Failed -> {
                errorMessage = joinInviteMessage(context, result.feedback)
                loading = false
            }
            JoinGroupInviteResult.Aborted -> {
                onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_joining_group)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator()
                joinedChatId != null -> {
                    // success dialog below
                }
                errorMessage != null -> {
                    // error dialog below
                }
                else -> Text(
                    stringResource(R.string.contacts_cannot_join_group),
                    color = LocalChatPalette.current.textSecondary,
                )
            }
        }
    }

    if (!loading && joinedChatId != null) {
        val chatId = joinedChatId!!
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.contacts_joined_group)) },
            text = {
                Text(joinedTitle ?: stringResource(R.string.chat_group))
            },
            confirmButton = {
                TextButton(onClick = { onJoined(chatId) }) {
                    Text(stringResource(R.string.contacts_enter_group), color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.chat_later), color = LocalChatPalette.current.textSecondary)
                }
            },
        )
    } else if (!loading && errorMessage != null) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.contacts_cannot_join_group)) },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.chat_acknowledge))
                }
            },
        )
    }
}

private fun joinInviteMessage(
    context: android.content.Context,
    feedback: com.maodouchat.contacts.QrScanFeedbackPolicy.Feedback,
): String = when (feedback.kind) {
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.INVALID_PAYLOAD ->
        context.getString(R.string.contacts_invalid_qr_message)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.SESSION_EXPIRED ->
        context.getString(R.string.error_session_expired)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.USER_NOT_FOUND ->
        context.getString(R.string.contacts_user_not_found_hint)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.INVITE_INVALID_OR_EXPIRED ->
        context.getString(R.string.contacts_invite_invalid_or_expired)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.INVITE_BLOCKED ->
        context.getString(R.string.contacts_invite_blocked)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.GROUP_FULL ->
        context.getString(R.string.contacts_invite_group_full)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.NETWORK ->
        context.getString(R.string.contacts_invite_network)
    com.maodouchat.contacts.QrScanFeedbackPolicy.Kind.UNKNOWN ->
        context.getString(R.string.contacts_join_group_failed)
}
