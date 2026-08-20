package com.maodouchat.ui.screen.explore

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 外部用户详情页 — 类似 t.me 的个人主页
 * 通过 chat.mdou.me/u/{username} 打开
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextGetResourceValueCall") // 资源字符串均在回调/协程内读取，非组合作用域
fun PublicProfileScreen(
    username: String,
    onBack: () -> Unit,
    onStartChat: (String) -> Unit = {},
    apiService: ApiService? = null,
    tokenManager: TokenManager? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<PublicProfileData?>(null) }
    // 重试计数：自增后触发 LaunchedEffect 重新拉取
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(username, retryKey) {
        loading = true
        error = null
        try {
            val response = ApiService.getPublicProfile(username).getOrNull()
            if (response?.ok == true && response.user != null) {
                val u = response.user
                profile = PublicProfileData(
                    id = u.id,
                    name = u.name,
                    username = u.username,
                    avatar = u.avatar,
                    status = u.status,
                    isOnline = u.isOnline,
                    isModerator = u.isModerator,
                    lastSeen = u.lastSeen
                )
            } else {
                error = context.getString(R.string.public_profile_not_found)
            }
        } catch (e: Exception) {
            error = context.getString(R.string.public_profile_load_failed) + ": " + (e.localizedMessage ?: context.getString(R.string.public_profile_network_error))
        } finally {
            loading = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.public_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                    titleContentColor = OnSurface,
                    navigationIconContentColor = OnSurface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                error != null -> {
                    // 守卫处单次断言捕获，后续不再需要 !!
                    val errorMessage = error!!
                    ProfileErrorView(
                        error = errorMessage,
                        onRetry = { retryKey++ }
                    )
                }
                profile != null -> {
                    // 9.219：守卫处单次断言捕获局部 profile——回调延迟执行时不再重复 !!（委托属性无法智能转换）
                    val loadedProfile = profile!!
                    ProfileContentView(
                        profile = loadedProfile,
                        onStartChat = { onStartChat(loadedProfile.id) },
                        onCopyLink = {
                            val link = "https://chat.mdou.me/u/$username"
                            @Suppress("DEPRECATION")
                            clipboard.setText(AnnotatedString(link))
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.public_profile_link_copied)) }
                        },
                        onShare = {
                            val link = "https://chat.mdou.me/u/$username"
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.public_profile_share_text, loadedProfile.name, link))
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.public_profile_share_chooser)))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileErrorView(error: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("😿", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalChatPalette.current.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(R.string.public_profile_retry))
            }
        }
    }
}

@Composable
private fun ProfileContentView(
    profile: PublicProfileData,
    onStartChat: () -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // 头像
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (profile.avatar != null && profile.avatar.isNotBlank()) {
                // 使用统一的 Avatar 组件（本地密钥隔离缓存）
                com.maodouchat.ui.component.Avatar(
                    name = profile.name,
                    avatarUrl = profile.avatar,
                    size = com.maodouchat.ui.component.AvatarSize.LG
                )
            } else {
                Text(
                    profile.name.firstOrNull()?.toString() ?: "?",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 用户名
        profile.username?.let { uname ->
            Text(
                "@$uname",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // 名称
        Text(
            profile.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 状态
        if (profile.status.isNotBlank()) {
            Text(
                profile.status,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 在线状态
        val statusText = if (profile.isOnline) {
            "🟢 " + stringResource(R.string.public_profile_online)
        } else {
            "💤 " + stringResource(R.string.public_profile_offline)
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            color = if (profile.isOnline) Color(0xFF059669) else TextHint
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        // 操作按钮
        Button(
            onClick = onStartChat,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.public_profile_start_chat), fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCopyLink,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.public_profile_copy_link), fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.public_profile_share), fontSize = 13.sp)
            }
        }
    }
}

/**
 * 公开个人主页数据
 */
data class PublicProfileData(
    val id: String,
    val name: String,
    val username: String? = null,
    val avatar: String? = null,
    val status: String = "",
    val isOnline: Boolean = false,
    val isModerator: Boolean = false,
    val lastSeen: Long = 0
)
