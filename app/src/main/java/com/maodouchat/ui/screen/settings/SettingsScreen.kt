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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.R
import com.maodouchat.security.SensitiveAction
import com.maodouchat.security.SensitiveActionGate
import com.maodouchat.ui.component.FloatingBottomBarContentPadding
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.MaodouchatTheme
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
    onOpenAgent: () -> Unit = {},
    onOpenModeration: () -> Unit = {},
    onOpenMyQrCode: () -> Unit = {},
    onOpenStarredMessages: () -> Unit = {},
    onOpenMyPosts: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
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
                    Text(stringResource(R.string.settings_title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            // 9.4xx：设置页是主 Tab，移除无导航作用的摆设返回按钮
            colors = com.maodouchat.ui.theme.liquidGlassTopAppBarColors()
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

                Spacer(modifier = Modifier.height(10.dp))

                SettingsGroup {
                    SettingsItem(icon = Icons.Outlined.Security, title = stringResource(R.string.settings_account_security), onClick = onOpenAccountSecurity)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.Flag, title = stringResource(R.string.settings_my_reports), onClick = onOpenMyReports)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.Block, title = stringResource(R.string.settings_blocked_users), onClick = onOpenBlockedUsers)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.AutoMirrored.Outlined.Article, title = stringResource(R.string.settings_my_posts), onClick = onOpenMyPosts)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.StarOutline, title = stringResource(R.string.settings_starred_messages), onClick = onOpenStarredMessages)
                }

                Spacer(modifier = Modifier.height(10.dp))

                SettingsGroup {
                    SettingsItem(icon = Icons.Outlined.Notifications, title = stringResource(R.string.settings_notifications), onClick = onOpenNotifications)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.PrivacyTip, title = stringResource(R.string.settings_privacy), onClick = { viewModel.openPrivacy() })
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.VerifiedUser, title = stringResource(R.string.settings_ai_privacy), onClick = onOpenAiPrivacy)
                    if (com.maodouchat.ai.AiEntryPolicy.shouldShowGlobalAiEntry(context)) {
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                        SettingsItem(icon = Icons.Outlined.AutoAwesome, title = stringResource(R.string.agent_title), onClick = onOpenAgent)
                    }
                    if (state.isModerator) {
                        HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                        SettingsItem(icon = Icons.Outlined.Security, title = stringResource(R.string.settings_moderation), onClick = onOpenModeration)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.Brightness6, title = stringResource(R.string.settings_general), onClick = onOpenGeneral)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(
                        icon = Icons.Outlined.Public,
                        title = stringResource(R.string.settings_server),
                        subtitle = stringResource(R.string.settings_server_subtitle),
                        onClick = onOpenServer
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.QrCode, title = stringResource(R.string.profile_my_qr), onClick = onOpenMyQrCode)
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.Share, title = stringResource(R.string.settings_share_profile),
                        onClick = {
                            val url = state.publicProfileUrl ?: com.maodouchat.network.ApiConfig.BASE_URL.trimEnd('/')
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.public_profile_share_text, state.userName, url))
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, context.getString(R.string.common_share)))
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.divider, modifier = Modifier.padding(start = 52.dp))
                    SettingsItem(icon = Icons.Outlined.Article, title = stringResource(R.string.about_title), onClick = onOpenAbout)
                }

                Spacer(modifier = Modifier.height(10.dp))

                SettingsGroup {
                    SettingsItem(
                        icon = null,
                        title = stringResource(R.string.settings_logout),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showLogoutConfirm = true }
                    )
                }

                // Dock is a floating capsule that sits above the system gesture bar; 96.dp
                // still leaves Sign out under the glass, so taps open About / switch tabs.
                Spacer(modifier = Modifier.height(FloatingBottomBarContentPadding + 72.dp))
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
            onlineVisibility = state.onlineVisibility,
            showStatus = state.showStatus,
            searchable = state.searchable,
            defaultPostVisibility = state.defaultPostVisibility,
            visibilityOptions = viewModel.visibilityOptions,
            isSaving = state.isSavingPrivacy,
            onShowOnlineChange = viewModel::onShowOnlineChange,
            onOnlineVisibilityChange = viewModel::onOnlineVisibilityChange,
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
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaodouDimens.ScreenPadding)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

@Composable
private fun SettingsItem(icon: ImageVector?, title: String, titleColor: Color = MaterialTheme.colorScheme.onSurface, subtitle: String? = null, onClick: () -> Unit) {
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
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable(interactionSource = interactionSource, indication = androidx.compose.material3.ripple(), onClick = onClick)
    ) {
        if (icon != null) {
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))) {
                Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
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

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SettingsScreenPreview() { MaodouchatTheme { SettingsScreen() } }
