package com.maodouchat.ui.screen.chatdetail.group

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.rememberSecretPageWatermarkPayload
import com.maodouchat.ui.component.secretPageBlindWatermark
import com.maodouchat.ui.screen.chatdetail.GroupDetailViewModel
import com.maodouchat.ui.theme.LocalChatPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("HardwareIds")
fun GroupEditScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chatId = viewModel.chatId
    var groupNameDraft by rememberSaveable(chatId) { mutableStateOf(state.groupName) }
    var announcementDraft by rememberSaveable(chatId) { mutableStateOf(state.groupAnnouncement) }
    var nicknameDraft by rememberSaveable(chatId) { mutableStateOf(state.myNickname) }
    var lastSyncedGroupName by remember(chatId) { mutableStateOf(state.groupName) }
    var lastSyncedAnnouncement by remember(chatId) { mutableStateOf(state.groupAnnouncement) }
    var lastSyncedNickname by remember(chatId) { mutableStateOf(state.myNickname) }
    var showAvatarFull by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::uploadGroupAvatar)
    }

    LaunchedEffect(chatId, state.groupName, state.groupAnnouncement, state.myNickname, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        if (groupNameDraft == lastSyncedGroupName || groupNameDraft.isBlank()) groupNameDraft = state.groupName
        lastSyncedGroupName = state.groupName
        if (announcementDraft == lastSyncedAnnouncement || announcementDraft.isBlank()) {
            announcementDraft = state.groupAnnouncement
        }
        lastSyncedAnnouncement = state.groupAnnouncement
        if (nicknameDraft == lastSyncedNickname || nicknameDraft.isBlank()) nicknameDraft = state.myNickname
        lastSyncedNickname = state.myNickname
    }

    val secretPagePayload = rememberSecretPageWatermarkPayload(
        isSecretChat = state.isSecretChat,
        userId = state.currentUserId,
        chatId = chatId,
        deviceHint = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .secretPageBlindWatermark(secretPagePayload)
    ) {
        GroupDetailFeedbackDialog(state, viewModel)
        if (showAvatarFull && !state.groupAvatar.isNullOrBlank()) {
            GroupAvatarPreview(
                avatarUrl = state.groupAvatar.orEmpty(),
                groupName = state.groupName,
                onDismiss = { showAvatarFull = false }
            )
        }
        Scaffold(
            containerColor = LocalChatPalette.current.chatBackground,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.group_detail_edit), color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.shadow(1.dp)
                )
            }
        ) { padding ->
            if (state.isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item(key = "edit_avatar", contentType = "edit_avatar") {
                        GroupEditAvatar(
                            groupName = state.groupName,
                            groupAvatar = state.groupAvatar,
                            canChangeAvatar = state.canManageGroup,
                            isUploadingAvatar = state.isUploadingAvatar,
                            onChangeAvatar = {
                                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onShowAvatarFull = { showAvatarFull = true }
                        )
                    }
                    item(key = "edit_settings_title", contentType = "section_header") {
                        SectionTitle(stringResource(R.string.group_detail_settings))
                    }
                    if (state.canManageGroup) {
                        item(key = "edit_group_name", contentType = "setting") {
                            SettingTextFieldRow(
                                label = stringResource(R.string.chat_group_name),
                                value = groupNameDraft,
                                onValueChange = { groupNameDraft = it.take(50) },
                                enabled = !state.isUpdating,
                                onSave = { viewModel.renameGroup(groupNameDraft) },
                                saveEnabled = groupNameDraft.trim().isNotBlank() && groupNameDraft.trim() != state.groupName
                            )
                        }
                        item(key = "edit_announcement", contentType = "setting") {
                            AnnouncementRow(
                                value = announcementDraft,
                                onValueChange = { announcementDraft = it.take(1200) },
                                enabled = !state.isUpdating,
                                onSave = { viewModel.updateAnnouncement(announcementDraft) },
                                saveEnabled = announcementDraft.trim() != state.groupAnnouncement,
                                canManage = true
                            )
                        }
                    } else {
                        item(key = "edit_read_only", contentType = "empty") {
                            EmptyRow(stringResource(R.string.group_detail_read_only_hint))
                        }
                    }
                    item(key = "edit_nickname", contentType = "setting") {
                        SettingTextFieldRow(
                            label = stringResource(R.string.group_detail_my_nickname),
                            value = nicknameDraft,
                            onValueChange = { nicknameDraft = it.take(100) },
                            enabled = !state.isUpdating,
                            onSave = { viewModel.setMyNickname(nicknameDraft) },
                            saveEnabled = nicknameDraft.trim() != state.myNickname
                        )
                    }
                    item(key = "edit_footer", contentType = "footer") { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
fun GroupEditAvatar(
    groupName: String,
    groupAvatar: String?,
    canChangeAvatar: Boolean,
    isUploadingAvatar: Boolean,
    onChangeAvatar: () -> Unit,
    onShowAvatarFull: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape)
                .clickable(
                    enabled = (canChangeAvatar && !isUploadingAvatar) || (!canChangeAvatar && !groupAvatar.isNullOrBlank())
                ) {
                    if (canChangeAvatar) onChangeAvatar() else onShowAvatarFull()
                },
            contentAlignment = Alignment.Center
        ) {
            Avatar(name = groupName, avatarUrl = groupAvatar, size = AvatarSize.LG)
            when {
                isUploadingAvatar -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp)
                )
                canChangeAvatar -> Icon(
                    Icons.Outlined.CameraAlt,
                    contentDescription = stringResource(R.string.group_detail_change_avatar),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(5.dp)
                )
            }
        }
        if (canChangeAvatar) {
            Text(
                stringResource(R.string.group_detail_change_avatar),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
