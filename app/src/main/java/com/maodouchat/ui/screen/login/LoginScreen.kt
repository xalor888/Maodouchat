package com.maodouchat.ui.screen.login

import android.annotation.SuppressLint
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.repository.PublicServerInfoRepository
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.MotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
// 资源字符串均在回调/协程内读取，非组合作用域
@SuppressLint("LocalContextGetResourceValueCall")
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    viewModel: LoginViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val motion = LocalMotionSettings.current
    val formScrollState = rememberScrollState()

    var animationPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animationPlayed = true }

    val enterProgress by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = if (!motion.animationsEnabled) snap() else tween(
            durationMillis = motion.duration(MotionTokens.Emphasized),
            easing = FastOutSlowInEasing
        ),
        label = "loginEnterProgress"
    )

    // 关闭系统动画（animator_duration_scale=0）时 motion.duration 返回 0，
    // infiniteRepeatable(0ms) 会直接抛 IllegalArgumentException 崩溃登录页——
    // 与 rememberMotionPulse 一致：动画禁用时退化为静态值。
    val floatY: Float = if (motion.animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "logoFloat")
        val animatedY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -4f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = motion.duration(2400).coerceAtLeast(1),
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoFloatY"
        )
        animatedY
    } else {
        0f
    }

    // 服务端全局状态（注册开关 / 邀请提示 / 维护模式）——登录页横幅与 tab 禁用依据
    var serverRegistrationOpen by remember { mutableStateOf<Boolean?>(null) }
    var serverInviteHint by remember { mutableStateOf<String?>(null) }
    var serverMaintenance by remember { mutableStateOf(false) }
    var serverMaintMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // G328c：传输层调用移到 data 层（同 ChatListServerFlags 的处理）。
        val raw = withContext(Dispatchers.IO) { PublicServerInfoRepository().publicStatus().orEmpty() }
        if (raw.isBlank()) return@LaunchedEffect
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@LaunchedEffect
        serverRegistrationOpen = if (o.has("registrationOpen")) o.optBoolean("registrationOpen") else null
        // optString 缺失键返回字面 "null"（非 blank）——需显式排除
        serverInviteHint = o.optString("inviteOnlyHint").takeIf { it.isNotBlank() && it != "null" }
        serverMaintenance = if (o.has("maintenance")) o.optBoolean("maintenance") else o.optBoolean("maintenanceMode", false)
        serverMaintMsg = o.optString("maintenanceMessage").takeIf { it.isNotBlank() && it != "null" }
    }

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoginSuccess()
    }
    LaunchedEffect(state.errorMessage, state.infoMessage) {
        if (!state.errorMessage.isNullOrBlank() || !state.infoMessage.isNullOrBlank()) {
            formScrollState.animateScrollTo(formScrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(formScrollState)
                .imePadding()
                // 9.247：edge-to-edge 下此前只有硬编码 top 56dp 没有状态栏 insets——
                // 打孔屏/刘海屏（状态栏 30-40dp）上 logo 顶到状态栏，普通机型又偏空；
                // 改吃真实状态栏高度 + 固定边距，全机型一致
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = MaodouDimens.ScreenPadding)
                .padding(top = 24.dp, bottom = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp)
            ) {
                LoginHeader(
                    enterProgress = enterProgress,
                    floatY = floatY,
                    animationsEnabled = motion.animationsEnabled,
                    serverRegistrationOpen = serverRegistrationOpen,
                    serverInviteHint = serverInviteHint,
                    serverMaintenance = serverMaintenance,
                    serverMaintMsg = serverMaintMsg,
                )
                Spacer(modifier = Modifier.height(32.dp))

                LoginTabRow(
                    selectedTab = state.selectedTab,
                    serverRegistrationOpen = serverRegistrationOpen,
                    enterProgress = enterProgress,
                    animationsEnabled = motion.animationsEnabled,
                    onTabSelected = viewModel::onTabSelected,
                )
                // Form
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.graphicsLayer {
                        alpha = enterProgress
                        translationY = if (motion.animationsEnabled) (1f - enterProgress) * 8.dp.toPx() else 0f
                    }
                ) {
                    LoginFormFields(
                        state = state,
                        onNameChange = viewModel::onNameChange,
                        onEmailChange = viewModel::onEmailChange,
                        onTotpCodeChange = viewModel::onTotpCodeChange,
                        onCodeChange = viewModel::onCodeChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onPasswordConfirmChange = viewModel::onPasswordConfirmChange,
                        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                        onSendVerificationCode = viewModel::sendVerificationCode,
                    )
                    LoginActions(
                        state = state,
                        onSubmit = viewModel::submit,
                        onOpenServer = onOpenServer,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer
                Text(
                    text = when (state.selectedTab) {
                        0 -> stringResource(R.string.login_footer)
                        1 -> stringResource(R.string.register_footer)
                        else -> stringResource(R.string.forgot_password_footer)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = enterProgress }
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun LoginScreenPreview() { MaodouchatTheme { LoginScreen() } }
