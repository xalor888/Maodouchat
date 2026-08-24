package com.maodouchat.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.maodouchat.R
import com.maodouchat.security.FakeChatManager
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 假聊天模式设置页（隐藏桌面图标已砍，不再提供入口）。
 * 所有配置仅存本机（按账号隔离），不同步服务器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeChatSettingsScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var fakeEnabled by remember { mutableStateOf(FakeChatManager.isEnabled(context)) }
    var hasPin by remember { mutableStateOf(FakeChatManager.hasPin(context)) }
    var relockOnBackground by remember { mutableStateOf(FakeChatManager.isRelockOnBackground(context)) }

    var showPinSetup by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.fake_chat_settings_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(modifier = Modifier.height(8.dp))

            // 假聊天模式主开关
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.fake_chat_mode), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.fake_chat_mode_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    }
                    Switch(
                        checked = fakeEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // FakeChat 保持关闭：setEnabled(true) 一律拒绝。
                                FakeChatManager.setEnabled(context, true)
                                fakeEnabled = FakeChatManager.isEnabled(context)
                            } else {
                                FakeChatManager.setEnabled(context, false)
                                fakeEnabled = false
                                FakeChatManager.lockNow(context)
                            }
                        }
                    )
                }
                if (fakeEnabled) {
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 44.dp))
                    // 修改解锁密码
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPinSetup = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.fake_chat_pin_change), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.fake_chat_pin_change_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = LocalChatPalette.current.chatInputBorder, modifier = Modifier.padding(start = 44.dp))
                    // 回前台重锁
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.fake_chat_relock), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.fake_chat_relock_subtitle), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                        }
                        Switch(
                            checked = relockOnBackground,
                            onCheckedChange = {
                                relockOnBackground = it
                                FakeChatManager.setRelockOnBackground(context, it)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 使用说明
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.fake_chat_guide_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.fake_chat_guide_1), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    Text(stringResource(R.string.fake_chat_guide_2), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                    Text(stringResource(R.string.fake_chat_guide_3), style = MaterialTheme.typography.bodySmall, color = LocalChatPalette.current.textSecondary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // 弹窗回调为非 @Composable lambda，字符串需在组合作用域内预取后引用
    val pinSavedMessage = stringResource(R.string.fake_chat_pin_saved)

    if (showPinSetup) {
        FakePinSetupDialog(
            isFirstTime = !hasPin,
            onDismiss = { showPinSetup = false },
            onSaved = { newPin ->
                FakeChatManager.setPin(context, newPin)
                hasPin = true
                showPinSetup = false
                FakeChatManager.setEnabled(context, true)
                fakeEnabled = FakeChatManager.isEnabled(context)
                Toast.makeText(context, pinSavedMessage, Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun FakePinSetupDialog(
    isFirstTime: Boolean,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatchMsg = stringResource(R.string.fake_chat_pin_mismatch)
    val invalidMsg = stringResource(R.string.fake_chat_pin_invalid)
    val valid = pin.isNotEmpty() && confirm.isNotEmpty() && error == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isFirstTime) R.string.fake_chat_pin_setup_title else R.string.fake_chat_pin_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.fake_chat_pin_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalChatPalette.current.textSecondary
                )
                PinField(
                    value = pin,
                    label = stringResource(R.string.fake_chat_pin_new),
                    onValueChange = { pin = it.take(12); error = null }
                )
                PinField(
                    value = confirm,
                    label = stringResource(R.string.fake_chat_pin_confirm),
                    onValueChange = { confirm = it.take(12); error = null }
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (pin != confirm) {
                        error = mismatchMsg
                    } else if (!FakeChatManager.isPinValid(pin)) {
                        error = invalidMsg
                    } else {
                        onSaved(pin)
                    }
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun PinField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (newValue.all(Char::isDigit)) onValueChange(newValue)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LocalChatPalette.current.chatInputBackground,
            unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = LocalChatPalette.current.chatInputBorder,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
