package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

/**
 * 密聊的「闸门」态 UI（G184 从 ChatDetailRoute composable 抽出）。
 *
 * 这些是**互斥的整页拦截态**：设备未登记 / 需要聊天锁 / 正在加载……
 * 它们和路由本身无耦合，只是各自渲染一整页。
 */

/**
 * B2 新设备风控（ndz）：设备未登记 → 密聊内容锁定，仅保留重新登记入口。
 *
 * 原先内联在 `ChatDetailRoute` 的 `} else if (deviceRiskLocked) {` 分支里。
 * 内联时它的收尾 `}` 与下一条 `else if (chatLockBlocking) {` 的起始 `{`
 * **在同一行**，括号配平无法单独确定边界（G184 踩过并记录）——
 * 所以改用「替换分支体」而不是「搬走整块」的方式抽出。
 */
@Composable
internal fun SecretNewDeviceRiskLocked(onRegisterClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                stringResource(R.string.secret_new_device_risk_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            TextButton(onClick = onRegisterClick) {
                Text(stringResource(R.string.secret_new_device_risk_register), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
