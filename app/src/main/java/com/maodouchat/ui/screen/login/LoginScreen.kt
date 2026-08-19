package com.maodouchat.ui.screen.login

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.PrimaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.MaodouDimens
import com.maodouchat.ui.theme.MaodouchatTheme
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Outline
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextHint
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.MotionTokens
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer

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

    val infiniteTransition = rememberInfiniteTransition(label = "logoFloat")
    val floatY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = motion.duration(2400),
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoFloatY"
    )

    // 服务端全局状态（注册开关 / 邀请提示 / 维护模式）——登录页横幅与 tab 禁用依据
    val context = LocalContext.current
    var serverRegistrationOpen by remember { mutableStateOf<Boolean?>(null) }
    var serverInviteHint by remember { mutableStateOf<String?>(null) }
    var serverMaintenance by remember { mutableStateOf(false) }
    var serverMaintMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val raw = withContext(Dispatchers.IO) { ApiService.getPublicStatus().getOrNull().orEmpty() }
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

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Surface, Surface, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f))
            )
        ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = MaodouDimens.ScreenPadding)
                .padding(top = 56.dp, bottom = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp)
            ) {
                // Logo
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            alpha = enterProgress
                            translationY = if (motion.animationsEnabled) {
                                floatY.dp.toPx() + (1f - enterProgress) * 16.dp.toPx()
                            } else 0f
                            scaleX = if (motion.animationsEnabled) 0.94f + (0.06f * enterProgress) else 1f
                            scaleY = if (motion.animationsEnabled) 0.94f + (0.06f * enterProgress) else 1f
                        }
                        .shadow(2.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, Color(0xFFE7E8E9), CircleShape)
                ) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(72.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Brand
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.graphicsLayer {
                        alpha = enterProgress
                        translationY = if (motion.animationsEnabled) (1f - enterProgress) * 12.dp.toPx() else 0f
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalChatPalette.current.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .graphicsLayer {
                            alpha = enterProgress
                            translationY = if (motion.animationsEnabled) (1f - enterProgress) * 10.dp.toPx() else 0f
                        }
                )

                // 服务器横幅（维护模式 / 邀请制提示）
                val serverBanner = when {
                    serverMaintenance -> buildString {
                        append(context.getString(R.string.login_maintenance_mode))
                        serverMaintMsg?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
                    }
                    serverRegistrationOpen == false && !serverInviteHint.isNullOrBlank() -> serverInviteHint
                    serverRegistrationOpen == false -> stringResource(R.string.login_register_closed)
                    else -> serverInviteHint
                }
                if (serverBanner != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = serverBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (serverMaintenance) Error else Primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .graphicsLayer { alpha = enterProgress }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Tab Row
                PrimaryTabRow(
                    selectedTabIndex = state.selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = Primary,
                    indicator = {
                        PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(state.selectedTab, matchContentSize = false),
                            color = MaterialTheme.colorScheme.primary,
                            height = 2.dp,
                        )
                    },
                    divider = { HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant) },
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .graphicsLayer {
                            alpha = enterProgress
                            translationY = if (motion.animationsEnabled) (1f - enterProgress) * 8.dp.toPx() else 0f
                        }
                ) {
                    Tab(selected = state.selectedTab == 0, onClick = { viewModel.onTabSelected(0) },
                        text = { Text(stringResource(R.string.login_tab), fontWeight = if (state.selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal) },
                        selectedContentColor = Primary, unselectedContentColor = TextSecondary)
                    Tab(selected = state.selectedTab == 1, onClick = {
                        if (serverRegistrationOpen == false) {
                            Toast.makeText(context, context.getString(R.string.login_register_closed), Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.onTabSelected(1)
                        }
                    },
                        text = { Text(stringResource(R.string.register_tab), fontWeight = if (state.selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal) },
                        selectedContentColor = Primary, unselectedContentColor = TextSecondary)
                    Tab(selected = state.selectedTab == 2, onClick = { viewModel.onTabSelected(2) },
                        text = { Text(stringResource(R.string.forgot_password_tab), fontWeight = if (state.selectedTab == 2) FontWeight.SemiBold else FontWeight.Normal) },
                        selectedContentColor = Primary, unselectedContentColor = TextSecondary)
                }

                // Form
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.graphicsLayer {
                        alpha = enterProgress
                        translationY = if (motion.animationsEnabled) (1f - enterProgress) * 8.dp.toPx() else 0f
                    }
                ) {
                    // 注册模式：用户名
                    if (state.selectedTab == 1) {
                        OutlinedTextField(
                            value = state.name, onValueChange = { viewModel.onNameChange(it) },
                            placeholder = { Text(stringResource(R.string.username), color = LocalChatPalette.current.textHint) },
                            leadingIcon = { Icon(Icons.Outlined.Person, null, tint = Outline) },
                            singleLine = true, shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                                focusedBorderColor = Primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                                cursorColor = Primary, focusedTextColor = OnSurface, unfocusedTextColor = OnSurface
                            ), modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 邮箱
                    OutlinedTextField(
                        value = state.email, onValueChange = { viewModel.onEmailChange(it) },
                        placeholder = { Text(stringResource(R.string.email_address), color = LocalChatPalette.current.textHint) },
                        leadingIcon = { Icon(Icons.Outlined.Email, null, tint = Outline) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                            focusedBorderColor = Primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                            cursorColor = Primary, focusedTextColor = OnSurface, unfocusedTextColor = OnSurface
                        ), modifier = Modifier.fillMaxWidth()
                    )

                    if (state.selectedTab == 0 && state.requiresTotp) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.totpCode,
                            onValueChange = { viewModel.onTotpCodeChange(it) },
                            placeholder = { Text(stringResource(R.string.login_totp_label), color = LocalChatPalette.current.textHint) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = Outline) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                                unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                                cursorColor = Primary,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 注册 / 找回密码：验证码
                    if (state.selectedTab == 1 || state.selectedTab == 2) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = state.code, onValueChange = { viewModel.onCodeChange(it) },
                                placeholder = { Text(stringResource(R.string.verification_code), color = LocalChatPalette.current.textHint) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                                    focusedBorderColor = Primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                                    cursorColor = Primary, focusedTextColor = OnSurface, unfocusedTextColor = OnSurface
                                ), modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { viewModel.sendVerificationCode() },
                                enabled = !state.isCodeSending && state.codeCountdown == 0
                            ) {
                                if (state.isCodeSending) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else if (state.codeCountdown > 0) {
                                    Text("${state.codeCountdown}s", color = LocalChatPalette.current.textSecondary)
                                } else {
                                    Text(stringResource(R.string.send_verification_code), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // 密码（找回密码时为新密码）
                    OutlinedTextField(
                        value = state.password, onValueChange = { viewModel.onPasswordChange(it) },
                        placeholder = {
                            Text(
                                if (state.selectedTab == 2) stringResource(R.string.new_password) else stringResource(R.string.password),
                                color = LocalChatPalette.current.textHint
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = Outline) },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.togglePasswordVisibility() }) {
                                Icon(if (state.passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                    if (state.passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password), tint = Outline)
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LocalChatPalette.current.chatInputBackground, unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                            focusedBorderColor = Primary, unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                            cursorColor = Primary, focusedTextColor = OnSurface, unfocusedTextColor = OnSurface
                        ), modifier = Modifier.fillMaxWidth()
                    )

                    // 注册：确认密码
                    if (state.selectedTab == 1) {
                        OutlinedTextField(
                            value = state.passwordConfirm,
                            onValueChange = { viewModel.onPasswordConfirmChange(it) },
                            placeholder = { Text(stringResource(R.string.login_confirm_password), color = LocalChatPalette.current.textHint) },
                            leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = Outline) },
                            singleLine = true,
                            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LocalChatPalette.current.chatInputBackground,
                                unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
                                cursorColor = Primary,
                                focusedTextColor = OnSurface,
                                unfocusedTextColor = OnSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 注册 / 找回：密码强度提示
                    if ((state.selectedTab == 1 || state.selectedTab == 2) && state.password.isNotEmpty()) {
                        val strength = remember(state.password) {
                            com.maodouchat.util.PasswordStrength.evaluate(state.password)
                        }
                        val strengthColor = when (strength.level) {
                            com.maodouchat.util.PasswordStrength.Level.WEAK -> Error
                            com.maodouchat.util.PasswordStrength.Level.FAIR -> Color(0xFFE67E22)
                            else -> Primary
                        }
                        val strengthLabel = when (strength.level) {
                            com.maodouchat.util.PasswordStrength.Level.WEAK -> stringResource(R.string.password_strength_weak)
                            com.maodouchat.util.PasswordStrength.Level.FAIR -> stringResource(R.string.password_strength_fair)
                            com.maodouchat.util.PasswordStrength.Level.STRONG -> stringResource(R.string.password_strength_strong)
                            com.maodouchat.util.PasswordStrength.Level.VERY_STRONG -> stringResource(R.string.password_strength_very_strong)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.password_strength_label), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                                Text(strengthLabel, style = MaterialTheme.typography.bodySmall, color = strengthColor)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                repeat(4) { idx ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .background(
                                                if (idx < strength.score) strengthColor else LocalChatPalette.current.chatInputBorder,
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }

                    // 错误 / 成功提示
                    state.errorMessage?.let { msg ->
                            Text(msg, color = Error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    state.infoMessage?.let { msg ->
                            Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 提交按钮
                    val btnInteractionSource = remember { MutableInteractionSource() }
                    val btnPressed by btnInteractionSource.collectIsPressedAsState()
                    val btnScale by animateFloatAsState(
                        targetValue = if (btnPressed) 0.97f else 1f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                        label = "loginBtnScale"
                    )
                    Button(
                        onClick = { viewModel.submit() },
                        enabled = !state.isLoading,
                        shape = RoundedCornerShape(MaodouDimens.ControlRadius),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        interactionSource = btnInteractionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .graphicsLayer {
                                scaleX = btnScale
                                scaleY = btnScale
                            }
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        } else {
                            val label = when (state.selectedTab) {
                                0 -> stringResource(R.string.login_tab)
                                1 -> stringResource(R.string.register_tab)
                                else -> stringResource(R.string.reset_password_action)
                            }
                            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp))
                        }
                    }

                    TextButton(
                        onClick = onOpenServer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.settings_server),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
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
