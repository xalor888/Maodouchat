package com.maodouchat.ui.screen.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

// 登录页顶部：logo / 品牌名 / 副标题 / 服务器状态横幅
@Composable
internal fun LoginHeader(
    enterProgress: Float,
    floatY: Float,
    animationsEnabled: Boolean,
    serverRegistrationOpen: Boolean?,
    serverInviteHint: String?,
    serverMaintenance: Boolean,
    serverMaintMsg: String?,
) {
    // Logo
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer {
                alpha = enterProgress
                translationY = if (animationsEnabled) {
                    floatY.dp.toPx() + (1f - enterProgress) * 16.dp.toPx()
                } else 0f
                scaleX = if (animationsEnabled) 0.94f + (0.06f * enterProgress) else 1f
                scaleY = if (animationsEnabled) 0.94f + (0.06f * enterProgress) else 1f
            }
            .shadow(2.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            // 9.248：硬编码浅灰边框在深色模式下生硬——改主题 token 自动适配
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
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
            translationY = if (animationsEnabled) (1f - enterProgress) * 12.dp.toPx() else 0f
        }.semantics { heading() }
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
                translationY = if (animationsEnabled) (1f - enterProgress) * 10.dp.toPx() else 0f
            }
    )

    // 服务器横幅（维护模式 / 邀请制提示）
    val serverBanner = when {
        serverMaintenance -> buildString {
            append(stringResource(R.string.login_maintenance_mode))
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
            color = if (serverMaintenance) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .graphicsLayer { alpha = enterProgress }
        )
    }
}
