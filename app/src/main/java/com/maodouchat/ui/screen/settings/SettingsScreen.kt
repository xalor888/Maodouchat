package com.maodouchat.ui.screen.settings

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.network.ApiService
import com.maodouchat.network.UserDto
import com.maodouchat.R
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Divider
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.PrimaryFixed
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.SurfaceContainerHigh
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
fun SettingsScreen(
    onLogout: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenAccountSecurity: () -> Unit = {},
    onOpenMyReports: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenGeneral: () -> Unit = {},
    onOpenAiPrivacy: () -> Unit = {},
    onOpenModeration: () -> Unit = {},
    onOpenMyQrCode: () -> Unit = {},
    onOpenStarredMessages: () -> Unit = {},
    onOpenMyPosts: () -> Unit = {},
    onOpenFakeChat: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val motion = LocalMotionSettings.current
    // rememberSaveable 保证旋转屏幕后不再重复播放入场动画
    var animPlayed by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sensitiveAuthTitle = stringResource(R.string.sensitive_auth_title)
    val sensitiveAuthLogout = stringResource(R.string.sensitive_auth_logout)
    val sensitiveAuthFailed = stringResource(R.string.sensitive_auth_failed)

    // 头像选择器
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.uploadAvatar(it) } }

    LaunchedEffect(Unit) { animPlayed = true }

    LaunchedEffect(state.isLoggedOut) {
        if (state.isLoggedOut) onLogout()
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccessMessage()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearErrorMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
        )

        Box(modifier = Modifier.fillMaxSize()) {
            // imePadding 防止软键盘遮挡输入框和保存/取消按钮
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                Spacer(modifier = Modifier.height(8.dp))

                // 个人资料卡（可编辑）
                AnimatedVisibility(visible = animPlayed, enter = fadeIn(tween(motion.duration(MotionTokens.Emphasized)))) {
                    ProfileCard(
                        name = state.userName,
                        userId = state.userId,
                        avatarUrl = state.userAvatar,
                        status = state.userStatus,
                        username = state.userUsername,
                        isEditing = state.isEditing,
                        editName = state.editName,
                        isUploading = state.isUploading,
                        isSaving = state.isSaving,
                        onEditNameChange = { viewModel.onEditNameChange(it) },
                        onStartEdit = { viewModel.startEditing() },
                        onSaveEdit = { viewModel.saveProfile() },
                        onCancelEdit = { viewModel.cancelEditing() },
                        onChangeAvatar = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveAvatar = viewModel::removeAvatar,
                        onOpenMyQr = onOpenMyQrCode,
                        onEditStatus = { viewModel.openStatusEditor() },
                        onSetUsername = { viewModel.openUsernameEditor() }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedVisibility(visible = animPlayed, enter = slideInVertically(tween(motion.duration(MotionTokens.Emphasized), motion.duration(40))) + fadeIn(tween(motion.duration(MotionTokens.Emphasized), motion.duration(40)))) {
                    SettingsGroup {
                        SettingsItem(icon = Icons.Outlined.Security, title = stringResource(R.string.settings_account_security), onClick = onOpenAccountSecurity)
                        SettingsItem(icon = Icons.Outlined.Flag, title = stringResource(R.string.settings_my_reports), onClick = onOpenMyReports)
                        SettingsItem(icon = Icons.Outlined.Block, title = stringResource(R.string.settings_blocked_users), onClick = onOpenBlockedUsers)
                        // 1.116：我的动态（作者主页视角）
                        SettingsItem(icon = Icons.AutoMirrored.Outlined.Article, title = stringResource(R.string.settings_my_posts), onClick = onOpenMyPosts)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedVisibility(visible = animPlayed, enter = slideInVertically(tween(motion.duration(MotionTokens.Emphasized), motion.duration(80))) + fadeIn(tween(motion.duration(MotionTokens.Emphasized), motion.duration(80)))) {
                    SettingsGroup {
                        SettingsItem(icon = Icons.Outlined.Notifications, title = stringResource(R.string.settings_notifications), onClick = onOpenNotifications)
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(icon = Icons.Outlined.PrivacyTip, title = stringResource(R.string.settings_privacy), onClick = { viewModel.openPrivacy() })
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(icon = Icons.Outlined.Security, title = stringResource(R.string.settings_blocked_users), onClick = { viewModel.openBlockedUsers() })
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(icon = Icons.Outlined.AutoAwesome, title = stringResource(R.string.settings_ai_privacy), onClick = onOpenAiPrivacy)
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        if (state.isModerator) {
                            SettingsItem(icon = Icons.Outlined.Security, title = stringResource(R.string.settings_moderation), onClick = onOpenModeration)
                            HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        }
                        SettingsItem(icon = Icons.Outlined.Brightness6, title = stringResource(R.string.settings_general), onClick = onOpenGeneral)
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(
                            icon = Icons.Outlined.StarOutline,
                            title = stringResource(R.string.settings_starred_messages),
                            onClick = onOpenStarredMessages
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(
                            icon = Icons.Outlined.VisibilityOff,
                            title = stringResource(R.string.settings_fake_chat),
                            onClick = onOpenFakeChat
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = stringResource(R.string.settings_floating_ball),
                            subtitle = stringResource(R.string.settings_floating_ball_subtitle),
                            onClick = { viewModel.toggleFloatingBall() }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(
                            icon = Icons.Outlined.Public,
                            title = stringResource(R.string.settings_server),
                            subtitle = stringResource(R.string.settings_server_subtitle),
                            onClick = onOpenServer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedVisibility(visible = animPlayed, enter = slideInVertically(tween(motion.duration(MotionTokens.Emphasized), motion.duration(100))) + fadeIn(tween(motion.duration(MotionTokens.Emphasized), motion.duration(100)))) {
                    SettingsGroup {
                        SettingsItem(icon = Icons.Outlined.QrCode, title = stringResource(R.string.profile_my_qr), onClick = onOpenMyQrCode)
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 56.dp))
                        SettingsItem(icon = Icons.Outlined.Share, title = stringResource(R.string.settings_share_profile),
                            onClick = {
                                val url = state.publicProfileUrl ?: "https://chat.mdou.me"
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.public_profile_share_text, state.userName, url))
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, context.getString(R.string.common_share)))
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedVisibility(visible = animPlayed, enter = slideInVertically(tween(motion.duration(MotionTokens.Emphasized), motion.duration(120))) + fadeIn(tween(motion.duration(MotionTokens.Emphasized), motion.duration(120)))) {
                    SettingsGroup {
                        SettingsItem(
                            icon = null,
                            title = stringResource(R.string.settings_logout),
                            titleColor = Error,
                            onClick = { showLogoutConfirm = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = { Text(stringResource(R.string.settings_logout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    SensitiveActionGate.confirm(
                        context = context,
                        action = SensitiveAction.LOGOUT,
                        title = sensitiveAuthTitle,
                        subtitle = sensitiveAuthLogout,
                        onSuccess = { viewModel.logout() },
                        onFailure = { msg ->
                            Toast.makeText(
                                context,
                                msg?.takeIf { it.isNotBlank() } ?: sensitiveAuthFailed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }) { Text(stringResource(R.string.settings_logout_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (state.showStatusDialog) {
        StatusEditorDialog(
            status = state.editStatus,
            isSaving = state.isSaving,
            errorMessage = state.errorMessage,
            onStatusChange = viewModel::onEditStatusChange,
            onPreset = viewModel::applyStatusPreset,
            onClear = { viewModel.onEditStatusChange("") },
            onDismiss = viewModel::closeStatusEditor,
            onSave = viewModel::saveStatus
        )
    }

    // 用户名编辑对话框
    if (state.showUsernameDialog) {
        UsernameEditorDialog(
            username = state.editUsername,
            isSaving = state.isSaving,
            errorMessage = state.errorMessage,
            onUsernameChange = viewModel::onEditUsernameChange,
            onDismiss = viewModel::closeUsernameEditor,
            onSave = viewModel::saveUsername,
            onClear = { viewModel.onEditUsernameChange("") }
        )
    }

    if (state.showPrivacyDialog) {
        PrivacyDialog(
            showOnline = state.showOnline,
            showStatus = state.showStatus,
            searchable = state.searchable,
            defaultPostVisibility = state.defaultPostVisibility,
            visibilityOptions = viewModel.visibilityOptions,
            isSaving = state.isSavingPrivacy,
            onShowOnlineChange = viewModel::onShowOnlineChange,
            onShowStatusChange = viewModel::onShowStatusChange,
            onSearchableChange = viewModel::onSearchableChange,
            onDefaultVisibilityChange = viewModel::onDefaultVisibilityChange,
            onDismiss = viewModel::closePrivacy,
            onSave = viewModel::savePrivacy
        )
    }

    if (state.showBlockedUsersDialog) {
        BlockedUsersDialog(
            blockedUsers = state.blockedUsers,
            isLoading = state.isLoadingBlockedUsers,
            isUpdating = state.isUpdatingBlockedUsers,
            onUnblock = viewModel::unblockUser,
            onRefresh = viewModel::loadBlockedUsers,
            onDismiss = viewModel::closeBlockedUsers
        )
    }
}

@Composable
private fun ProfileCard(
    name: String,
    userId: String,
    avatarUrl: String?,
    status: String = "",
    username: String? = null,
    isEditing: Boolean,
    editName: String,
    isUploading: Boolean,
    isSaving: Boolean,
    onEditNameChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onChangeAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onOpenMyQr: () -> Unit = {},
    onEditStatus: () -> Unit = {},
    onSetUsername: () -> Unit = {}
) {
    var showAvatarMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // clickable 放在最后一个 padding 之后，使卡片整体可点击
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaodouDimens.ScreenPadding)
            .shadow(2.dp, RoundedCornerShape(MaodouDimens.CardRadius)).clip(RoundedCornerShape(MaodouDimens.CardRadius))
            .background(MaterialTheme.colorScheme.surface).padding(MaodouDimens.ScreenPadding)
            .clickable { if (!isEditing) onStartEdit() }
    ) {
        // 头像（点击更换）
        Box(modifier = Modifier.clickable(enabled = !isUploading) { showAvatarMenu = true }) {
            Box(modifier = Modifier.border(1.dp, SurfaceContainerHigh, RoundedCornerShape(32.dp)).clip(RoundedCornerShape(32.dp))) {
                Avatar(name = name, avatarUrl = avatarUrl, size = AvatarSize.LG)
            }
            // 相机图标叠加
            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp).align(Alignment.Center), strokeWidth = 2.dp)
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = stringResource(R.string.profile_change_avatar), tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            DropdownMenu(expanded = showAvatarMenu, onDismissRequest = { showAvatarMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_change_avatar)) },
                    leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                    onClick = {
                        showAvatarMenu = false
                        onChangeAvatar()
                    }
                )
                if (!avatarUrl.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_remove_avatar), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showAvatarMenu = false
                            onRemoveAvatar()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (isEditing) {
                // 编辑模式
                OutlinedTextField(
                    value = editName,
                    onValueChange = { if (it.length <= 30) onEditNameChange(it) },
                    singleLine = true,
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    supportingText = {
                        Text(
                            "${editName.length}/30",
                            color = if (editName.length > 25) Error else TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    TextButton(onClick = onSaveEdit, enabled = !isSaving) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isSaving) stringResource(R.string.profile_saving) else stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onCancelEdit, enabled = !isSaving) {
                        Icon(Icons.Outlined.Close, null, tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_cancel), color = LocalChatPalette.current.textSecondary)
                    }
                }
            } else {
                // 显示模式
                Text(name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                // 9.281：ID 视觉减重——完整 u_uuid 太长，展示缩写（前段+…+尾段），
                // 点按复制完整 ID（加好友/客服排查仍可用全值）
                val clipboard = LocalClipboardManager.current
                val idCopiedMsg = stringResource(R.string.profile_id_copied)
                val shortId = if (userId.length > 16) userId.take(10) + "…" + userId.takeLast(4) else userId
                Text(
                    stringResource(R.string.profile_maodou_id, shortId),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textHint,
                    modifier = Modifier.clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(userId))
                        Toast.makeText(context, idCopiedMsg, Toast.LENGTH_SHORT).show()
                    }
                )
                // 用户名显示（可点击设置）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onSetUsername() }
                ) {
                    val uname = username?.let { "@$it" } ?: stringResource(R.string.settings_set_username)
                    Text(
                        text = uname,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (username != null) Primary else Outline
                    )
                    if (username == null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_set_username),
                            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = status.ifBlank { stringResource(R.string.status_empty_placeholder) },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.isBlank()) Outline else TextSecondary,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditStatus() }
                )
            }
        }

        if (!isEditing) {
            IconButton(onClick = onOpenMyQr) {
                Icon(Icons.Outlined.QrCode, contentDescription = stringResource(R.string.profile_my_qr), tint = LocalChatPalette.current.textSecondary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StatusEditorDialog(
    status: String,
    isSaving: Boolean,
    errorMessage: String?,
    onStatusChange: (String) -> Unit,
    onPreset: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.status_title)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.status_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                OutlinedTextField(
                    value = status,
                    onValueChange = onStatusChange,
                    placeholder = { Text(stringResource(R.string.status_hint)) },
                    singleLine = false,
                    maxLines = 3,
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    supportingText = {
                        Text(
                            "${status.length}/${com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH}",
                            color = if (status.length > com.maodouchat.util.CustomStatusPolicy.MAX_LENGTH - 10) Error else TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.status_presets), style = MaterialTheme.typography.labelLarge, color = LocalChatPalette.current.textSecondary)
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    com.maodouchat.util.CustomStatusPolicy.PRESETS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                            row.forEach { preset ->
                                FilterChip(
                                    selected = status == preset,
                                    onClick = { onPreset(preset) },
                                    label = { Text(statusPresetLabel(preset)) }
                                )
                            }
                        }
                    }
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !isSaving) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear, enabled = !isSaving && status.isNotEmpty()) {
                    Text(stringResource(R.string.status_clear), color = LocalChatPalette.current.textSecondary)
                }
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}

@Composable
private fun PrivacyDialog(
    showOnline: Boolean,
    showStatus: Boolean,
    searchable: Boolean,
    defaultPostVisibility: String,
    visibilityOptions: List<Pair<String, String>>,
    isSaving: Boolean,
    onShowOnlineChange: (Boolean) -> Unit,
    onShowStatusChange: (Boolean) -> Unit,
    onSearchableChange: (Boolean) -> Unit,
    onDefaultVisibilityChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.settings_privacy)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                PrivacySwitchRow(
                    title = stringResource(R.string.privacy_show_online_title),
                    subtitle = stringResource(R.string.privacy_show_online_subtitle),
                    checked = showOnline,
                    enabled = !isSaving,
                    onCheckedChange = onShowOnlineChange
                )
                PrivacySwitchRow(
                    title = stringResource(R.string.privacy_show_status_title),
                    subtitle = stringResource(R.string.privacy_show_status_subtitle),
                    checked = showStatus,
                    enabled = !isSaving,
                    onCheckedChange = onShowStatusChange
                )
                PrivacySwitchRow(
                    title = stringResource(R.string.privacy_searchable_title),
                    subtitle = stringResource(R.string.privacy_searchable_subtitle),
                    checked = searchable,
                    enabled = !isSaving,
                    onCheckedChange = onSearchableChange
                )
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.privacy_default_post_visibility), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        visibilityOptions.forEach { (value, _) ->
                            FilterChip(
                                selected = defaultPostVisibility == value,
                                enabled = !isSaving,
                                onClick = { onDefaultVisibilityChange(value) },
                                label = { Text(privacyVisibilityLabel(value)) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !isSaving) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BlockedUsersDialog(
    blockedUsers: List<UserDto>,
    isLoading: Boolean,
    isUpdating: Boolean,
    onUnblock: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(blockedUsers, query) {
        val q = query.trim()
        if (q.isEmpty()) blockedUsers
        else blockedUsers.filter { user ->
            user.name.contains(q, ignoreCase = true) ||
                user.id.contains(q, ignoreCase = true) ||
                user.status.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_blocked_users)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                when {
                    isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.blocked_loading), color = LocalChatPalette.current.textSecondary)
                        }
                    }
                    blockedUsers.isEmpty() -> Text(stringResource(R.string.blocked_empty), color = LocalChatPalette.current.textSecondary)
                    else -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.blocked_search_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (filtered.isEmpty()) {
                            Text(stringResource(R.string.blocked_search_empty), color = LocalChatPalette.current.textSecondary)
                        } else {
                            filtered.forEach { user ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.SM, isOnline = user.isOnline)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(user.name.ifBlank { user.id }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOf(user.id, user.status.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LocalChatPalette.current.textHint
                                        )
                                    }
                                    TextButton(enabled = !isUpdating, onClick = { onUnblock(user.id) }) {
                                        Text(stringResource(R.string.blocked_unblock))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = !isLoading && !isUpdating) { Text(stringResource(R.string.common_refresh)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isUpdating) { Text(stringResource(R.string.common_done)) } }
    )
}

@Composable
private fun privacyVisibilityLabel(value: String): String = when (value) {
    "CONTACTS" -> stringResource(R.string.explore_visibility_contacts)
    "PRIVATE" -> stringResource(R.string.explore_visibility_private)
    else -> stringResource(R.string.explore_visibility_public)
}

/** UI labels for [CustomStatusPolicy.PRESETS] wire values (multi-device keeps Chinese wire text). */
@Composable
private fun statusPresetLabel(wire: String): String = when (wire) {
    "在线" -> stringResource(R.string.status_preset_online)
    "忙碌" -> stringResource(R.string.status_preset_busy)
    "开会中" -> stringResource(R.string.status_preset_meeting)
    "请勿打扰" -> stringResource(R.string.status_preset_dnd)
    "马上回来" -> stringResource(R.string.status_preset_brb)
    "休假中" -> stringResource(R.string.status_preset_vacation)
    "学习中" -> stringResource(R.string.status_preset_studying)
    "通勤中" -> stringResource(R.string.status_preset_commuting)
    "专注中" -> stringResource(R.string.status_preset_focusing)
    "吃饭中" -> stringResource(R.string.status_preset_eating)
    "旅游中" -> stringResource(R.string.status_preset_traveling)
    "运动中" -> stringResource(R.string.status_preset_exercising)
    "工作中" -> stringResource(R.string.status_preset_working)
    "通话中" -> stringResource(R.string.status_preset_on_call)
    "开车中" -> stringResource(R.string.status_preset_driving)
    "游戏中" -> stringResource(R.string.status_preset_gaming)
    "睡觉中" -> stringResource(R.string.status_preset_sleeping)
    "写作中" -> stringResource(R.string.status_preset_writing)
    "出差中" -> stringResource(R.string.status_preset_business_trip)
    "充电中" -> stringResource(R.string.status_preset_charging)
    "听歌中" -> stringResource(R.string.status_preset_listening)
    "阅读中" -> stringResource(R.string.status_preset_reading)
        "观影中" -> stringResource(R.string.status_preset_watching)
        "做饭中" -> stringResource(R.string.status_preset_cooking)
    else -> wire
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaodouDimens.ScreenPadding)
            .shadow(2.dp, RoundedCornerShape(MaodouDimens.CardRadius)).clip(RoundedCornerShape(MaodouDimens.CardRadius))
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

@Composable
private fun SettingsItem(icon: ImageVector?, title: String, titleColor: Color = OnSurface, subtitle: String? = null, onClick: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "settingsItemPressScale"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .padding(horizontal = MaodouDimens.ScreenPadding, vertical = MaodouDimens.ItemGap)
            .clickable(interactionSource = interactionSource, indication = androidx.compose.material3.ripple(), onClick = onClick)
    ) {
        if (icon != null) {
            // 9.275：TG 式逐项图标配色——按图标稳定哈希到一组柔和调色板，
            // 每个设置项图标颜色不同（同名图标保持一致），更接近 Telegram 观感
            val iconTint = settingsIconTint(icon)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).background(iconTint.copy(alpha = 0.16f), RoundedCornerShape(8.dp))) {
                Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Spacer(modifier = Modifier.width(44.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
    }
}

/**
 * 9.275：按图标 name 稳定哈希选一个柔和调色板色。
 * 同一图标（ImageVector 单例，name 固定）始终得到同一颜色，不同图标错开，
 * 观感接近 Telegram 设置页逐项彩色图标。
 */
private fun settingsIconTint(icon: ImageVector): Color {
    val palette = listOf(
        Color(0xFFFF9500), // 橙
        Color(0xFF34A853), // 绿
        Color(0xFF3390EC), // 蓝
        Color(0xFF9B6BD6), // 紫
        Color(0xFFE85D5D), // 红
        Color(0xFF23B5A9), // 青
        Color(0xFFE0709B), // 粉
        Color(0xFF6B7BD6)  // 靛蓝
    )
    val index = (icon.name.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[index]
}

@Composable
private fun UsernameEditorDialog(
    username: String,
    isSaving: Boolean,
    errorMessage: String?,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.settings_username_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_username_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    placeholder = { Text(stringResource(R.string.settings_username_placeholder)) },
                    singleLine = true,
                    leadingIcon = { Text("@", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(MaodouDimens.SmallRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    supportingText = {
                        Text(
                            "${username.length}/50",
                            color = if (username.length > 45) Error else TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    // 9.287：修复 URL 域名重复嵌套（字符串模板已含前缀又传了完整 URL），
                    // 并改为跟随当前服务器地址（自建部署不再显示错误的 chat.mdou.me）
                    stringResource(
                        R.string.settings_username_profile_url,
                        com.maodouchat.network.ApiConfig.BASE_URL.removePrefix("https://").removePrefix("http://").trimEnd('/') + "/u/" + username
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (username.length >= 3) Primary else TextHint
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = username.length >= 3 && !isSaving) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SettingsScreenPreview() { MaodouchatTheme { SettingsScreen() } }
