package com.maodouchat.ui.screen.login

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.PrimaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.theme.LocalChatPalette

// 登录 / 注册 / 找回密码三 tab。注册 tab 在服务器关闭注册时弹 Toast 拦截。
@Composable
internal fun LoginTabRow(
    selectedTab: Int,
    serverRegistrationOpen: Boolean?,
    enterProgress: Float,
    animationsEnabled: Boolean,
    onTabSelected: (Int) -> Unit,
) {
    val context = LocalContext.current
    // Tab Row
    PrimaryTabRow(
        selectedTabIndex = selectedTab,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = {
            PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedTab, matchContentSize = false),
                color = MaterialTheme.colorScheme.primary,
                height = 2.dp,
            )
        },
        divider = { HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant) },
        modifier = Modifier
            .padding(bottom = 24.dp)
            .graphicsLayer {
                alpha = enterProgress
                translationY = if (animationsEnabled) (1f - enterProgress) * 8.dp.toPx() else 0f
            }
    ) {
        Tab(selected = selectedTab == 0, onClick = { onTabSelected(0) },
            text = { Text(stringResource(R.string.login_tab), fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal) },
            selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = LocalChatPalette.current.textSecondary)
        Tab(selected = selectedTab == 1, onClick = {
            if (serverRegistrationOpen == false) {
                Toast.makeText(context, context.getString(R.string.login_register_closed), Toast.LENGTH_SHORT).show()
            } else {
                onTabSelected(1)
            }
        },
            text = { Text(stringResource(R.string.register_tab), fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal) },
            selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = LocalChatPalette.current.textSecondary)
        Tab(selected = selectedTab == 2, onClick = { onTabSelected(2) },
            text = { Text(stringResource(R.string.forgot_password_tab), fontWeight = if (selectedTab == 2) FontWeight.SemiBold else FontWeight.Normal) },
            selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = LocalChatPalette.current.textSecondary)
    }
}
