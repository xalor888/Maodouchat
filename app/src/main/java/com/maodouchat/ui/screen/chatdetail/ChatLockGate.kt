package com.maodouchat.ui.screen.chatdetail

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.Background
import com.maodouchat.ui.theme.Error
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.Surface
import com.maodouchat.ui.theme.TextSecondary

/**
 * 会话 PIN 锁屏：4-8 位数字 PIN，解锁后才能进入 ChatDetail
 */
@Composable
fun ChatLockGate(
    chatName: String,
    onUnlock: (pin: String, onResult: (Boolean) -> Unit) -> Unit,
    onForgotPin: () -> Unit = {}
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val wrongPinText = stringResource(R.string.chat_lock_wrong_pin)
    val pinLengthText = stringResource(R.string.chat_lock_pin_length)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape).background(Primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Primary, modifier = Modifier.size(36.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.chat_lock_title), style = MaterialTheme.typography.titleLarge, color = OnSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.chat_lock_enter_pin, chatName), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(8) { i ->
                    val filled = i < pin.length
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 40.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(if (filled) Primary else Surface),
                        contentAlignment = Alignment.Center
                    ) {
                        if (filled) Text("•", color = androidx.compose.ui.graphics.Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(28.dp))
            NumberPad(
                onDigit = { d ->
                    if (pin.length < 8) {
                        pin += d
                        error = null
                    }
                },
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                onSubmit = {
                    if (verifying) {
                        // 等待异步 PIN 校验结果，防连点重复提交
                    } else if (pin.length in 4..8) {
                        verifying = true
                        onUnlock(pin) { ok ->
                            verifying = false
                            if (ok) {
                                pin = ""
                                error = null
                            } else {
                                error = wrongPinText
                                pin = ""
                            }
                        }
                    } else {
                        error = pinLengthText
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onForgotPin) { Text(stringResource(R.string.chat_lock_forgot_pin), color = TextSecondary) }
        }
    }
}

@Composable
private fun NumberPad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "DEL")
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(modifier = Modifier.size(72.dp))
                        "DEL" -> Box(
                            modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = onBackspace) {
                                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.chat_lock_delete_digit), tint = OnSurface)
                            }
                        }
                        else -> Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .clickableNumber { onDigit(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(key, color = OnSurface, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .height(48.dp)
                .width(220.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(Primary)
                .clickableNumber { onSubmit() },
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.chat_lock_unlock), color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Modifier.clickableNumber(onClick: () -> Unit): Modifier =
    this.clickable { onClick() }
