package com.maodouchat.ui.screen.explore

import kotlin.math.min
import com.maodouchat.data.repository.NearbyNetworkRepository
import com.maodouchat.network.ApiService
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.AndroidViewModel
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.component.EmptyState
import com.maodouchat.ui.component.EmptyStateType
import com.maodouchat.ui.component.PullToRefreshLayout
import com.maodouchat.util.NearbyPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import com.maodouchat.ui.theme.LocalChatPalette
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 「附近的人」页（G114 从 `ExploreSubScreens.kt` 拆出，原 324 行）。
 *
 * 附近的人列表 + 距离显示 + 点击进聊天。含 `NearbyItem`（单行）与
 * `formatNearbyDistance`（距离本地化：<1km 用米，否则用 km）。
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
                        if (state.isSharing) stringResource(R.string.explore_nearby_visible)
                        else stringResource(R.string.explore_nearby_disabled),
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
                            val optionLabel = NumberFormat.getNumberInstance().apply {
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

@Composable
private fun NearbyItem(person: NearbyPerson, onClick: () -> Unit) {
    val user = person.user
    val distanceText = if (user.isOnline) {
        stringResource(R.string.explore_nearby_distance_online, formatNearbyDistance(person.distanceMeters))
    } else {
        formatNearbyDistance(person.distanceMeters)
    }
    val updatedText = if (person.locationUpdatedAt > 0L) {
        val relative = DateUtils.getRelativeTimeSpanString(
            person.locationUpdatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
        stringResource(R.string.explore_nearby_updated, relative)
    } else {
        null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.MD, isOnline = user.isOnline)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (user.isOnline) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary
                )
                if (updatedText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = updatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
            Icon(Icons.Outlined.NearMe, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun formatNearbyDistance(distanceMeters: Int): String {
    val (res, arg) = nearbyDistanceLabel(distanceMeters)
    return if (arg == null) stringResource(res) else stringResource(res, arg)
}

/**
 * 距离 → (文案资源, 实参)（G163 从 `formatNearbyDistance` 抽出的纯判定）。
 *
 * 抽开之前这段和 `stringResource` 耦在一起，只能靠仪器测试覆盖；
 * 现在返回二元组，普通 JVM 单测就能逐分支断言。
 *
 * 两个细节都有产品理由，改时必须连测试一起改：
 * - **米那一支 `coerceAtLeast(100)`**：定位精度有限，显示「约 3 米」比「约 0 米」更可信；
 * - **公里那一支固定一位小数**：`NumberFormat` 默认对 2.0 显示 "2"，
 *   加上 `minimumFractionDigits = 1` 才会显示 "2.0"，与米的精度观感一致。
 */
internal fun nearbyDistanceLabel(distanceMeters: Int): Pair<Int, Any?> =
    if (distanceMeters < 1_000) {
        R.string.explore_nearby_distance_meters to distanceMeters.coerceAtLeast(100)
    } else {
        val formatter = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        R.string.explore_nearby_distance_km to formatter.format(distanceMeters / 1_000.0)
    }

data class NearbyPerson(val user: User, val distanceMeters: Int, val locationUpdatedAt: Long)

data class NearbyUiState(
    val items: List<NearbyPerson> = emptyList(),
    val isLoading: Boolean = false,
    val isSharing: Boolean = false,
    val expiresAt: Long = 0,
    val radiusKm: Double = NearbyPolicy.DEFAULT_RADIUS_KM,
    val errorMessage: String? = null
)

class NearbyViewModel(application: Application) : AndroidViewModel(application) {
    private fun nearbyAllowed(): Boolean {
        if (RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.NEARBY)) return true
        _uiState.update { it.copy(errorMessage = text(R.string.feature_disabled_by_admin), isLoading = false) }
        return false
    }

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    /** 8.38：重叠刷新代际计数（旧响应不得覆盖新响应）。 */
    private var refreshGeneration = 0

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun locationErrorText(error: Throwable): String = when (
        (error as? com.maodouchat.util.LocationException)?.failure
    ) {
        com.maodouchat.util.LocationFailure.PERMISSION_REQUIRED -> text(R.string.location_error_permission)
        com.maodouchat.util.LocationFailure.SERVICES_DISABLED -> text(R.string.location_error_services_disabled)
        com.maodouchat.util.LocationFailure.UNAVAILABLE, null -> text(R.string.location_error_unavailable)
    }

    init { loadStatus() }

    fun setRadiusKm(radiusKm: Double) {
        val next = NearbyPolicy.normalizeRadiusKm(radiusKm)
        val previous = _uiState.value.radiusKm
        if (kotlin.math.abs(previous - next) < 0.01) return
        _uiState.update { it.copy(radiusKm = next) }
        if (_uiState.value.isSharing) refresh()
    }

    fun loadStatus() {
        if (!nearbyAllowed()) return
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(items = emptyList(), isLoading = false, errorMessage = text(R.string.error_session_expired)) }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                NearbyNetworkRepository().status().onSuccess { status ->
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                    ) {
                        return@onSuccess
                    }
                    _uiState.update {
                        it.copy(
                            isSharing = status.sharing,
                            expiresAt = status.expiresAt,
                            isLoading = false,
                            items = if (status.sharing) it.items else emptyList()
                        )
                    }
                    if (status.sharing) refresh()
                }.onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: text(R.string.explore_nearby_status_failed)) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw error
            }
        }
    }

    fun enableSharing() {
        if (!nearbyAllowed()) return
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                com.maodouchat.util.LocationProvider.currentLocation(getApplication()).fold(
                    onSuccess = { location ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            _uiState.update { it.copy(isLoading = false) }
                            return@fold
                        }
                        NearbyNetworkRepository().updateLocation(latitude = location.latitude, longitude = location.longitude).fold(
                            onSuccess = { status ->
                                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = ownerUserId,
                                )
                                ) {
                                    return@fold
                                }
                                _uiState.update { it.copy(isSharing = status.sharing, expiresAt = status.expiresAt, isLoading = false) }
                                refresh()
                            },
                            onFailure = { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.explore_nearby_share_failed)) } }
                        )
                    },
                    onFailure = { error -> _uiState.update { it.copy(isLoading = false, errorMessage = locationErrorText(error)) } }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
                throw error
            }
        }
    }

    fun stopSharing() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = text(R.string.error_session_expired))
            }
            return
        }
        viewModelScope.launch {
            // 8.58：先使所有在途 refresh 失效——否则关闭共享时进行中的 refresh 会把位置
            // 重新广播到服务端（stop 晚到被反转，位置对他人可见直到 TTL）
            ++refreshGeneration
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            NearbyNetworkRepository().stopSharing().fold(
                onSuccess = {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                    ) {
                        return@fold
                    }
                    _uiState.update { it.copy(isSharing = false, expiresAt = 0, items = emptyList(), errorMessage = null) }
                },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message ?: text(R.string.explore_nearby_stop_failed)) } }
            )
        }
    }

    fun refresh() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val pre = _uiState.value
        val sharingValid = pre.isSharing && pre.expiresAt > System.currentTimeMillis()
        if (!sharingValid) {
            // 分享已过期或未开始：与服务器同步权威状态，绝不静默重新广播位置（同意缺口）
            if (!pre.isSharing) return
            loadStatus()
            return
        }
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = text(R.string.error_session_expired))
            }
            return
        }
        val radiusKm = NearbyPolicy.normalizeRadiusKm(_uiState.value.radiusKm)
        // 8.38：代际计数防止重叠刷新互相覆盖——慢响应先回、快响应后回时旧数据/旧失败态
        // 不得覆盖新结果（下拉刷新 / 半径切换 / enableSharing 回调会并发触发 refresh）
        val generation = ++refreshGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    if (generation == refreshGeneration) _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                if (com.maodouchat.util.LocationProvider.hasLocationPermission(getApplication())) {
                    com.maodouchat.util.LocationProvider.currentLocation(getApplication()).onSuccess { location ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@onSuccess
                        }
                        // 8.58：POST 前再校验——关闭共享后（stopSharing 已递增代际）跳过广播，
                        // 杜绝在途 refresh 把位置重新广播
                        if (generation != refreshGeneration || !_uiState.value.isSharing) return@onSuccess
                        NearbyNetworkRepository().updateLocation(latitude = location.latitude, longitude = location.longitude).onSuccess { status ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = ownerUserId,
                            )
                            ) {
                                return@onSuccess
                            }
                            if (generation != refreshGeneration) return@onSuccess
                            _uiState.update { it.copy(expiresAt = status.expiresAt) }
                        }
                    }
                }
                NearbyNetworkRepository().nearbyUsers(radiusKm = radiusKm).fold(
                    onSuccess = { dtos ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@fold
                        }
                        if (generation != refreshGeneration) return@fold
                        val items = dtos.map { dto ->
                            NearbyPerson(
                                user = User(dto.user.id, dto.user.name, dto.user.avatar, dto.user.email, dto.user.isOnline, dto.user.status),
                                distanceMeters = dto.distanceMeters,
                                locationUpdatedAt = dto.locationUpdatedAt
                            )
                        }.sortedWith { a, b ->
                            NearbyPolicy.compareNearby(
                                isOnlineA = a.user.isOnline,
                                distanceA = a.distanceMeters,
                                updatedAtA = a.locationUpdatedAt,
                                isOnlineB = b.user.isOnline,
                                distanceB = b.distanceMeters,
                                updatedAtB = b.locationUpdatedAt
                            )
                        }
                        _uiState.update { it.copy(items = items, isLoading = false) }
                    },
                    onFailure = { error ->
                        if (generation != refreshGeneration) return@fold
                        _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: text(R.string.explore_nearby_load_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (generation == refreshGeneration) _uiState.update { it.copy(isLoading = false) }
                throw error
            }
        }
    }
}
