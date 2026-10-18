package com.maodouchat.ui.screen.explore

import android.Manifest

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.PullToRefreshLayout
import com.maodouchat.util.NearbyPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「附近的人」页（G114 从 `ExploreSubScreens.kt` 拆出，原 324 行）。
 *
 * 附近的人列表 + 距离显示 + 点击进聊天。行组件簇（`NearbyItem`、
 * `formatNearbyDistance`、距离 ThreadLocal、`nearbyDistanceLabel`）
 * 已抽出同包 `ExploreNearbyRows.kt`，`NearbyScreen` 留此文件。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onBack: () -> Unit = {},
    onOpenChat: (User) -> Unit = {},
    viewModel: NearbyViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nearbyPermissionMsg = stringResource(R.string.explore_nearby_permission)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            com.maodouchat.util.LocationProvider.hasLocationPermission(context)
        if (granted) viewModel.enableSharing()
        else Toast.makeText(context, nearbyPermissionMsg, Toast.LENGTH_SHORT).show()
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isSharing, state.expiresAt) {
        if (!state.isSharing || state.expiresAt <= 0L) return@LaunchedEffect
        while (isActive) {
            val remaining = NearbyPolicy.remainingVisibleMs(state.expiresAt, System.currentTimeMillis())
            if (remaining <= 0L) {
                // 服务端 TTL 已过：同步权威状态（翻转 isSharing=false 并清空列表），避免陈旧“可见”横幅与静默重广播
                viewModel.loadStatus()
                break
            }
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val remainingMs = remember(state.expiresAt, nowMs, state.isSharing) {
        if (!state.isSharing) 0L else NearbyPolicy.remainingVisibleMs(state.expiresAt, nowMs)
    }
    val remainingHint = when {
        !state.isSharing -> stringResource(R.string.explore_nearby_disabled_hint)
        remainingMs <= 0L -> stringResource(R.string.explore_nearby_remaining_soon)
        remainingMs < 60_000L -> stringResource(R.string.explore_nearby_remaining_soon)
        else -> stringResource(
            R.string.explore_nearby_remaining,
            ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explore_nearby), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = state.isSharing && !state.isLoading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 无障碍：开关是这一行唯一的可点目标——挂行标题，TalkBack 读「开关」时带出状态名。
            val sharingLabel = stringResource(
                if (state.isSharing) R.string.explore_nearby_visible else R.string.explore_nearby_disabled
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = if (state.isSharing) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textHint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sharingLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        remainingHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalChatPalette.current.textSecondary
                    )
                }
                Switch(
                    checked = state.isSharing,
                    enabled = !state.isLoading,
                    modifier = Modifier.semantics { contentDescription = sharingLabel },
                    onCheckedChange = { enabled ->
                        if (!enabled) viewModel.stopSharing()
                        else if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) viewModel.enableSharing()
                        else locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                )
            }

            if (state.isSharing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.explore_nearby_radius_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalChatPalette.current.textSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NearbyPolicy.RADIUS_OPTIONS_KM.forEach { option ->
                            val selected = kotlin.math.abs(state.radiusKm - option) < 0.01
                            val optionLabel = nearbyRadiusFormat.get().apply {
                                maximumFractionDigits = if (option % 1.0 == 0.0) 0 else 1
                            }.format(option)
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setRadiusKm(option) },
                                enabled = !state.isLoading,
                                label = {
                                    Text(stringResource(R.string.explore_nearby_radius_km, optionLabel))
                                }
                            )
                        }
                    }
                    if (state.items.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            pluralStringResource(R.plurals.explore_nearby_count, state.items.size, state.items.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalChatPalette.current.textHint
                        )
                    }
                }
            }

            state.errorMessage?.let {
                Text(
                    it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = LocalChatPalette.current.unreadRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PullToRefreshLayout(
                    isRefreshing = state.isLoading && state.isSharing,
                    onRefresh = { if (state.isSharing) viewModel.refresh() }
                ) {
                    when {
                        state.isLoading && state.items.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        !state.isSharing -> {
                            // 8.52 UX：未开启分享时给出引导与直接开启动作（此前只有孤零图标）
                            EmptyState(
                                type = EmptyStateType.GENERIC,
                                title = stringResource(R.string.explore_nearby_disabled),
                                subtitle = stringResource(R.string.explore_nearby_disabled_hint),
                                actionText = stringResource(R.string.explore_nearby_enable),
                                onAction = {
                                    if (com.maodouchat.util.LocationProvider.hasLocationPermission(context)) {
                                        viewModel.enableSharing()
                                    } else {
                                        locationPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        state.items.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    stringResource(R.string.explore_nearby_empty),
                                    modifier = Modifier.align(Alignment.Center),
                                    color = LocalChatPalette.current.textHint
                                )
                            }
                        }
                        else -> {
                            var nearbySearch by rememberSaveable { mutableStateOf("") }
                            val filteredPeople = remember(state.items, nearbySearch) {
                                val query = nearbySearch.trim()
                                if (query.isBlank()) {
                                    state.items
                                } else {
                                    state.items.filter {
                                        it.user.displayName.contains(query, ignoreCase = true) ||
                                            it.user.name.contains(query, ignoreCase = true) ||
                                            it.user.id.contains(query, ignoreCase = true) ||
                                            it.user.status.contains(query, ignoreCase = true)
                                    }
                                }
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (state.items.size >= 4) {
                                    OutlinedTextField(
                                        value = nearbySearch,
                                        onValueChange = { nearbySearch = it.take(100) },
                                        singleLine = true,
                                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                        placeholder = { Text(stringResource(R.string.explore_nearby_search_hint)) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                if (filteredPeople.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            stringResource(R.string.explore_nearby_search_empty),
                                            modifier = Modifier.align(Alignment.Center),
                                            color = LocalChatPalette.current.textHint
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(filteredPeople, key = { it.user.id }, contentType = { "nearby_person" }) { person ->
                                            NearbyItem(person = person, onClick = { onOpenChat(person.user) })
                }
            }
        }
    }
}
                    }
                }
            }
        }
    }
}
