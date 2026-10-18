package com.maodouchat.ui.screen.chatdetail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.ui.theme.LocalChatPalette

// 群详情页顶栏：原在 GroupDetailScreen 的 Scaffold topBar 内联 lambda，纯搬移不改判断。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDetailTopBar(
    state: GroupDetailUiState,
    chatId: String,
    onBack: () -> Unit,
    onEditGroup: (String) -> Unit,
    onOpenGroupInvite: (String) -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                stringResource(if (state.isChannel) R.string.chat_detail_channel_header else R.string.group_detail_title),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
            }
        },
        actions = {
            IconButton(
                onClick = { onEditGroup(chatId) },
                enabled = !state.isLoading
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.group_detail_edit),
                    tint = if (state.isLoading) LocalChatPalette.current.textHint else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = { onOpenGroupInvite(chatId) },
                enabled = state.canManageGroup && !state.isLoadingInvite
            ) {
                Icon(
                    Icons.Outlined.QrCode,
                    contentDescription = stringResource(R.string.group_detail_invite_qr),
                    tint = if (state.canManageGroup) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.shadow(1.dp)
    )
}
