package com.maodouchat.ui.screen.lock

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.maodouchat.R
import com.maodouchat.security.FakeChatManager
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.Surface as SurfaceColor

/**
 * 假聊天模式前台界面：看起来是一个普通的「消息」App。
 *
 * - 仅展示预置脚本会话，不加载任何真实数据；
 * - 长按标题栏弹出隐藏的密码解锁框，输入正确密码后进入真实 App；
 * - 系统返回键按普通 App 行为退出到桌面（不暴露真实内容）。
 */
@Composable
fun FakeChatScreen(
    onUnlocked: () -> Unit = {},
    onFailed: () -> Unit = {}
) {
    val context = LocalContext.current
    val motion = LocalMotionSettings.current
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var failedPulse by remember { mutableStateOf(false) }
    val shakeScale by animateFloatAsState(
        targetValue = if (failedPulse) 0.96f else 1f,
        animationSpec = if (motion.animationsEnabled) spring(dampingRatio = 0.5f, stiffness = 480f) else snap(),
        label = "fakeChatFailurePulse"
    )

    // 返回键：像普通 App 一样退到桌面，绝不落到真实界面
    BackHandler {
        (context as? android.app.Activity)?.finish()
    }

    Surface(color = Background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FakeChatTopBar(
                onUnlockRequest = { showPinDialog = true },
                modifier = Modifier.scale(shakeScale)
            )
            FakeMessageList()
            FakeChatInputBar()
        }
    }

    if (showPinDialog) {
        FakePinDialog(
            onConfirm = { pin ->
                if (FakeChatManager.checkPin(context, pin)) {
                    showPinDialog = false
                    FakeChatManager.markUnlocked(context)
                    onUnlocked()
                    true
                } else {
                    // 假装是输错密码的普通弹窗，不暴露任何真实信息
                    failedPulse = !failedPulse
                    onFailed()
                    false
                }
            },
            onDismiss = { showPinDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FakeChatTopBar(onUnlockRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                // 长按标题 = 隐藏解锁入口
                Column(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { /* 普通点击无反应 */ },
                            onLongClick = onUnlockRequest
                        )
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.fake_chat_peer_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.fake_chat_peer_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { /* 普通聊天界面返回行为 */ }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            actions = {
                IconButton(onClick = { /* 无操作 */ }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = null, tint = LocalChatPalette.current.textSecondary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            )
        )
        // 9.282：公告条仅在有内容时渲染（朋友单聊人设下空公告不再占位）
        val announcement = stringResource(R.string.fake_chat_announcement)
        if (announcement.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    announcement,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = LocalChatPalette.current.textSecondary,
                    modifier = Modifier.size(18.dp).padding(end = 12.dp)
                )
            }
        }
    }
}

private data class FakeMessage(val text: String, val isMine: Boolean, val time: String)

// 9.282：示例对话去客服腔——改为真实朋友间的日常对话，降低「一眼假」感
private val fakeMessages = listOf(
    FakeMessage("晚上打球不？老地方", false, "18:02"),
    FakeMessage("几点", true, "18:05"),
    FakeMessage("七点半，老周说他也能来", false, "18:05"),
    FakeMessage("行，那我下班直接过去", true, "18:06"),
    FakeMessage("带瓶水就行，场地我订好了", false, "18:07"),
    FakeMessage("对了上次那个视频你存了吗", true, "18:09"),
    FakeMessage("存了，回去发你", false, "18:10"),
    FakeMessage("ok", true, "18:10"),
    FakeMessage("晚上见", false, "18:11"),
)

@Composable
private fun ColumnScope.FakeMessageList() {
    val listState = rememberLazyListState()
    val lastIndex = fakeMessages.lastIndex
    LaunchedEffect(Unit) {
        listState.scrollToItem(lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(fakeMessages, key = { it.hashCode() }) { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
            ) {
                if (!message.isMine) {
                    Avatar(name = stringResource(R.string.fake_chat_peer_name), avatarUrl = null, size = AvatarSize.SM)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (message.isMine) 14.dp else 4.dp,
                            bottomEnd = if (message.isMine) 4.dp else 14.dp
                        ),
                        color = if (message.isMine) Primary else SurfaceColor,
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.isMine) Color.White else OnSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FakeChatInputBar() {
    var draft by rememberSaveable { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(200) },
            placeholder = { Text(stringResource(R.string.fake_chat_input_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                focusedBorderColor = LocalChatPalette.current.chatInputBorder,
                unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                cursorColor = Primary,
                focusedTextColor = OnSurface,
                unfocusedTextColor = OnSurface
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = { draft = "" },
            enabled = draft.isNotBlank()
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.fake_chat_send),
                tint = if (draft.isNotBlank()) Primary else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun FakePinDialog(
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pin by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var lockoutMs by remember { mutableStateOf(FakeChatManager.lockoutRemainingMs(context)) }
    val wrongPinMsg = stringResource(R.string.fake_chat_wrong_pin)
    val lockoutSeconds = ((lockoutMs + 999L) / 1000L).toInt().coerceAtLeast(1)
    val supportingMsg = if (lockoutMs > 0L) {
        stringResource(R.string.fake_chat_pin_lockout, lockoutSeconds)
    } else if (error) {
        wrongPinMsg
    } else {
        null
    }
    LaunchedEffect(lockoutMs) {
        if (lockoutMs <= 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(250L)
        lockoutMs = FakeChatManager.lockoutRemainingMs(context)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.fake_chat_pin_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.fake_chat_pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary,
                    textAlign = TextAlign.Center
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 12 && it.all(Char::isDigit)) {
                            pin = it
                            error = false
                        }
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                        cursorColor = Primary,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface
                    ),
                    isError = error || lockoutMs > 0L,
                    supportingText = supportingMsg?.let { msg ->
                        { Text(msg, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = FakeChatManager.isPinValid(pin) && lockoutMs <= 0L,
                onClick = {
                    if (!onConfirm(pin)) {
                        error = true
                        lockoutMs = FakeChatManager.lockoutRemainingMs(context)
                    }
                }
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
