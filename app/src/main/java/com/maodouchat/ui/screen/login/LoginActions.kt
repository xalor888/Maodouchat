package com.maodouchat.ui.screen.login

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.R
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.ServerIdentity
import com.maodouchat.ui.theme.MaodouDimens
import java.net.URI

// 表单操作区：提交按钮与当前服务器身份入口
@Composable
internal fun LoginActions(
    state: LoginUiState,
    onSubmit: () -> Unit,
    onOpenServer: () -> Unit,
) {
    // 提交按钮
    val btnInteractionSource = remember { MutableInteractionSource() }
    val btnPressed by btnInteractionSource.collectIsPressedAsState()
    val btnScale by animateFloatAsState(
        targetValue = if (btnPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "loginBtnScale"
    )
    Button(
        onClick = { onSubmit() },
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
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        } else {
            val label = when (state.selectedTab) {
                0 -> stringResource(R.string.login_tab)
                1 -> stringResource(R.string.register_tab)
                else -> stringResource(R.string.reset_password_action)
            }
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp))
        }
    }

    // 9.204：第三方服务器模式下登录前明示当前服务器身份，避免误登
    val serverIdentity by ServerIdentity.current.collectAsState()
    val serverLabel = if (ApiConfig.isUsingRuntimeServer) {
        val host = runCatching { URI(ApiConfig.BASE_URL).host }
            .getOrNull() ?: ApiConfig.BASE_URL
        serverIdentity?.name?.takeIf(String::isNotBlank)?.let { "$it · $host" } ?: host
    } else {
        stringResource(R.string.settings_server)
    }
    TextButton(
        onClick = onOpenServer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            serverLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
