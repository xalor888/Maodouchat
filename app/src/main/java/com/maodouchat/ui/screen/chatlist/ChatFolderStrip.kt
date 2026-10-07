package com.maodouchat.ui.screen.chatlist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.maodouchat.R
import com.maodouchat.util.ChatFolderPolicy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatFolderStrip(
    folders: List<com.maodouchat.util.ChatFolder>,
    selectedFolderId: String?,
    secretChatCount: Int,
    lockedChatCount: Int,
    unreadInFolder: (String) -> Int,
    onSelectFolder: (String?) -> Unit,
    onManage: () -> Unit,
    onCreate: () -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FolderChip(stringResource(R.string.chat_folder_all), selectedFolderId.isNullOrBlank(), 0) { onSelectFolder(null) }
        FolderChip(stringResource(R.string.chat_folder_unread), selectedFolderId == ChatFolderPolicy.SYSTEM_UNREAD_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_UNREAD_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_UNREAD_ID) }
        FolderChip(stringResource(R.string.chat_folder_groups), selectedFolderId == ChatFolderPolicy.SYSTEM_GROUPS_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_GROUPS_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_GROUPS_ID) }
        FolderChip(stringResource(R.string.chat_folder_direct), selectedFolderId == ChatFolderPolicy.SYSTEM_DIRECT_ID, unreadInFolder(ChatFolderPolicy.SYSTEM_DIRECT_ID)) { onSelectFolder(ChatFolderPolicy.SYSTEM_DIRECT_ID) }
        if (secretChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID) {
            FolderChip(stringResource(R.string.chat_folder_secret), selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID, secretChatCount) { onSelectFolder(ChatFolderPolicy.SYSTEM_SECRET_ID) }
        }
        if (lockedChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID) {
            FolderChip(stringResource(R.string.chat_folder_locked), selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID, lockedChatCount) { onSelectFolder(ChatFolderPolicy.SYSTEM_LOCKED_ID) }
        }
        folders.forEach { folder ->
            FolderChip(folder.name, selectedFolderId == folder.id, unreadInFolder(folder.id)) { onSelectFolder(folder.id) }
        }
        TextButton(
            onClick = onCreate,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text(stringResource(R.string.chat_folder_create), style = MaterialTheme.typography.labelMedium) }
        TextButton(
            onClick = onManage,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text(stringResource(R.string.chat_folder_manage), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
internal fun FolderChip(label: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val chipLabel = if (badge > 0) "$label ${if (badge > 99) "99+" else badge}" else label
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(chipLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.padding(end = 6.dp).height(32.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.outline,
        )
    )
}
