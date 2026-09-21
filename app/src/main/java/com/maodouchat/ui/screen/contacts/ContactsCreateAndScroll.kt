package com.maodouchat.ui.screen.contacts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 通讯录的「新建与滚动」一族（G134 从 `ContactsScreen.kt` 拆出，原 363 行）。
 *
 * 五个声明：`NewGroupDialog`（选人建群：搜索/多选/已选条）、
 * `NewChannelDialog`（建频道：名称/简介/公开性）、`ContactItem`（联系人行）、
 * `AlphabetScroller`（字母快速滚动条）、`findLetterIndex`（按字母找列表首索引）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@Composable
internal fun NewGroupDialog(
    contacts: List<User>,
    onDismiss: () -> Unit,
    onConfirm: (groupName: String, members: List<User>) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var memberQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectedMembers = remember(selectedIds, contacts) { contacts.filter { it.id in selectedIds } }
    val filteredContacts = remember(contacts, memberQuery) {
        val q = memberQuery.trim()
        val base = if (q.isEmpty()) contacts
        else contacts.filter { user ->
            user.displayName.contains(q, ignoreCase = true) ||
                user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.email.contains(q, ignoreCase = true) ||
                user.nickname?.contains(q, ignoreCase = true) == true
        }
        base.sortedWith(compareByDescending<User> { it.isOnline }.thenBy { it.displayName.lowercase() })
    }
    val canCreate = groupName.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_new_group)) },
        text = {
            Column {
                TextField(
                    value = groupName,
                    onValueChange = { groupName = it.take(50) },
                    placeholder = { Text(stringResource(R.string.contacts_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = memberQuery,
                    onValueChange = { memberQuery = it.take(120) },
                    placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.contacts_group_members_optional),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textHint
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.contacts_select_members, selectedMembers.size), style = MaterialTheme.typography.labelLarge, color = LocalChatPalette.current.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                // 群成员选择列表：可滚动，最大 50% 屏幕高度
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(
                            if (memberQuery.isNotBlank()) R.string.contacts_new_group_search_empty
                            else R.string.contacts_empty
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.5f).heightIn(min = 160.dp, max = 360.dp)) {
                        items(filteredContacts, key = { it.id }, contentType = { "member_candidate" }) { user ->
                            val selected = user.id in selectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedIds = if (selected) selectedIds - user.id else selectedIds + user.id
                                }.padding(vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + user.id else selectedIds - user.id
                                    }
                                )
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    val subtitle = when {
                                        user.isOnline -> stringResource(R.string.contacts_online)
                                        user.email.isNotBlank() -> user.email
                                        else -> stringResource(R.string.contacts_offline)
                                    }
                                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (user.isOnline) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(groupName.trim(), selectedMembers) },
                enabled = canCreate
            ) { Text(stringResource(R.string.contacts_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 新建广播频道：创建者单向广播，订阅者只读。创建时可选初始订阅者（可留空，仅自己）。
 */
@Composable
internal fun NewChannelDialog(
    contacts: List<User>,
    onDismiss: () -> Unit,
    onConfirm: (channelName: String, members: List<User>) -> Unit
) {
    var channelName by remember { mutableStateOf("") }
    var memberQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectedMembers = remember(selectedIds, contacts) { contacts.filter { it.id in selectedIds } }
    val filteredContacts = remember(contacts, memberQuery) {
        val q = memberQuery.trim()
        val base = if (q.isEmpty()) contacts
        else contacts.filter { user ->
            user.displayName.contains(q, ignoreCase = true) ||
                user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.email.contains(q, ignoreCase = true) ||
                user.nickname?.contains(q, ignoreCase = true) == true
        }
        base.sortedWith(compareByDescending<User> { it.isOnline }.thenBy { it.displayName.lowercase() })
    }
    val canCreate = channelName.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_channel_create_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chat_channel_create_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                TextField(
                    value = channelName,
                    onValueChange = { channelName = it.take(50) },
                    placeholder = { Text(stringResource(R.string.chat_channel_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = memberQuery,
                    onValueChange = { memberQuery = it.take(120) },
                    placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.contacts_select_members, selectedMembers.size), style = MaterialTheme.typography.labelLarge, color = LocalChatPalette.current.textSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                if (filteredContacts.isEmpty()) {
                    Text(
                        stringResource(
                            if (memberQuery.isNotBlank()) R.string.contacts_new_group_search_empty
                            else R.string.contacts_empty
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textHint,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxHeight(0.5f).heightIn(min = 160.dp, max = 360.dp)) {
                        items(filteredContacts, key = { it.id }, contentType = { "channel_candidate" }) { user ->
                            val selected = user.id in selectedIds
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedIds = if (selected) selectedIds - user.id else selectedIds + user.id
                                }.padding(vertical = 6.dp)
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + user.id else selectedIds - user.id
                                    }
                                )
                                Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(user.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    val subtitle = if (user.isOnline) stringResource(R.string.contacts_online) else stringResource(R.string.contacts_offline)
                                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (user.isOnline) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(channelName.trim(), selectedMembers) },
                enabled = canCreate
            ) { Text(stringResource(R.string.chat_channel_create_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContactItem(user: User, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "contactPressScale"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Avatar(name = user.displayName, avatarUrl = user.avatar, size = AvatarSize.MD, isOnline = user.isOnline)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            val subtitle = when {
                !user.nickname.isNullOrBlank() && user.nickname != user.name -> user.name
                user.isOnline -> stringResource(R.string.contacts_online)
                user.status.isNotBlank() -> com.maodouchat.ui.component.localizedCustomStatusLabel(user.status)
                else -> stringResource(R.string.contacts_offline)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = if (user.isOnline && user.nickname.isNullOrBlank()) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun AlphabetScroller(
    letters: List<String>,
    onLetterClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allLetters = remember { ('A'..'Z').map { it.toString() } + "#" }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val hapticContext = LocalContext.current

    Box(modifier = modifier.fillMaxHeight().padding(end = 4.dp)) {
        // Active letter bubble indicator
        val bubbleScale by animateFloatAsState(
            targetValue = if (activeLetter != null) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "letterBubbleScale"
        )
        if (bubbleScale > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-36).dp)
                    .size(36.dp)
                    .graphicsLayer {
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                        alpha = bubbleScale
                    }
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeLetter ?: "",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val itemHeight = size.height / allLetters.size
                            val index = (offset.y / itemHeight).toInt().coerceIn(0, allLetters.lastIndex)
                            val letter = allLetters[index]
                            if (letter in letters) {
                                activeLetter = letter
                                com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                                onLetterClick(letter)
                            }
                        },
                        onDragEnd = { activeLetter = null },
                        onDragCancel = { activeLetter = null }
                    ) { change, _ ->
                        change.consume()
                        val y = change.position.y
                        val itemHeight = size.height / allLetters.size
                        val index = (y / itemHeight).toInt().coerceIn(0, allLetters.lastIndex)
                        val letter = allLetters[index]
                        if (letter in letters && letter != activeLetter) {
                            activeLetter = letter
                            com.maodouchat.util.HapticGate.perform(hapticContext, haptic, HapticFeedbackType.TextHandleMove)
                            onLetterClick(letter)
                        }
                    }
                }
        ) {
            allLetters.forEach { letter ->
                val isActive = letter == activeLetter
                val textColor = if (letter in letters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (isActive) 13.sp else 10.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = textColor,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

internal fun findLetterIndex(
    grouped: Map<String, List<User>>,
    incomingCount: Int,
    outgoingCount: Int,
    groupInviteCount: Int,
    targetLetter: String
): Int {
    val leadingFixedItems = com.maodouchat.contacts.ContactsIndexPolicy.leadingFixedItemCount(
        incomingCount = incomingCount,
        outgoingCount = outgoingCount,
        groupInviteCount = groupInviteCount
    )
    val ordered = grouped.keys.toList()
    val sizes = grouped.mapValues { it.value.size }
    return com.maodouchat.contacts.ContactsIndexPolicy.letterListIndex(
        orderedLetters = ordered,
        sizes = sizes,
        targetLetter = targetLetter,
        leadingFixedItems = leadingFixedItems
    )
}
