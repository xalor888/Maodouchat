package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.screen.chatdetail.group.GroupMemberSectionUtils.roleLabel
import com.maodouchat.ui.theme.LocalChatPalette

data class GroupFeatureAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
fun GroupHeader(
    groupName: String,
    groupAvatar: String?,
    memberCount: Int,
    myRole: String,
    modifier: Modifier = Modifier,
    onShowAvatarFull: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Avatar(
                name = groupName,
                avatarUrl = groupAvatar,
                size = AvatarSize.LG,
                modifier = Modifier.clickable(enabled = !groupAvatar.isNullOrBlank(), onClick = onShowAvatarFull)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = groupName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = pluralStringResource(R.plurals.group_detail_header_summary, memberCount, memberCount, roleLabel(myRole)),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GroupAnnouncementCard(
    announcement: String,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(announcement) { mutableStateOf(false) }
    var overflowsFourLines by remember(announcement) { mutableStateOf(false) }
    val canToggle = expanded || overflowsFourLines

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(enabled = canToggle) { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.group_detail_announcement),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = announcement.ifBlank { stringResource(R.string.group_detail_no_announcement) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (announcement.isBlank()) LocalChatPalette.current.textHint else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) overflowsFourLines = result.hasVisualOverflow
                }
            )
            if (canToggle) {
                Text(
                    text = stringResource(if (expanded) R.string.chat_transcript_collapse else R.string.chat_transcript_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun GroupFeaturesCard(
    actions: List<GroupFeatureAction>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        actions.chunked(2).forEachIndexed { rowIndex, rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupFeatureButton(rowActions[0], Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                )
                if (rowActions.size == 2) {
                    GroupFeatureButton(rowActions[1], Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowIndex < actions.chunked(2).lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            }
        }
    }
}

@Composable
fun GroupFeatureButton(action: GroupFeatureAction, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(104.dp)
            .clickable(onClick = action.onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SearchableSectionHeader(
    text: String,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true,
    trailingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        if (showSearch) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(
                        if (searchExpanded) R.string.chat_search_close else R.string.chat_search_action
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        trailingContent()
    }
}

@Composable
fun CollapsibleGroupSearchField(
    visible: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        TextField(
            value = value,
            onValueChange = { onValueChange(it.take(120)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(placeholder) },
            modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            colors = groupTextFieldColors(),
            shape = RoundedCornerShape(10.dp)
        )
    }
}

@Composable
fun SettingTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            colors = groupTextFieldColors(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.common_save)) }
    }
}

@Composable
fun AnnouncementRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    canManage: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.group_detail_announcement), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = 3,
            maxLines = 6,
            placeholder = { Text(if (canManage) stringResource(R.string.group_detail_announcement_input) else stringResource(R.string.group_detail_no_announcement)) },
            modifier = Modifier.fillMaxWidth(),
            colors = groupTextFieldColors(),
            shape = RoundedCornerShape(10.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${value.length}/1200", style = MaterialTheme.typography.labelSmall, color = LocalChatPalette.current.textHint, modifier = Modifier.weight(1f))
            if (canManage) {
                TextButton(onClick = { onValueChange("") }, enabled = enabled && value.isNotBlank()) { Text(stringResource(R.string.common_clear)) }
                Spacer(modifier = Modifier.width(6.dp))
                Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.group_detail_save_announcement)) }
            }
        }
    }
}

@Composable
fun groupTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LocalChatPalette.current.chatInputBackground,
    unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
    disabledContainerColor = LocalChatPalette.current.chatInputBackground,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
    disabledIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = LocalChatPalette.current.textHint
)
