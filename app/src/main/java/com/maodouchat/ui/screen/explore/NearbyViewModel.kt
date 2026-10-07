package com.maodouchat.ui.screen.explore

import com.maodouchat.data.repository.NearbyNetworkRepository
import com.maodouchat.util.RuntimeFlags
import android.app.Application
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.R
import com.maodouchat.data.model.User
import com.maodouchat.util.NearbyPolicy
import kotlinx.coroutines.launch

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
