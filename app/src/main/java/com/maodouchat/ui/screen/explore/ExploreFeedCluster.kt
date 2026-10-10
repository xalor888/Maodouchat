package com.maodouchat.ui.screen.explore

import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.R
import com.maodouchat.security.BackgroundSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.maodouchat.explore.policy.ExploreDraftPolicy
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.explore.policy.ExplorePaging

// 信息流加载一族：刷新/加载更多/隐私默认可见性。纯搬移，调用点与协程语义不变。
    fun ExploreOrchestrator.loadPrivacyDefaults() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            _uiState.update { it.copy(isVisibilityReady = true) }
            return
        }
        val generation = ++privacyDefaultsGeneration
        privacyDefaultsJob?.cancel()
        val job = scope.launch {
            try {
                if (!BackgroundSessionGate.mayContinue(ownerUserId)) return@launch
                AccountSecurityNetworkRepository().privacy().fold(
                    onSuccess = { privacy ->
                        if (privacyDefaultsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            val defaultVisibility = ExploreDraftPolicy.normalizeVisibility(privacy.defaultPostVisibility)
                            _uiState.update { current ->
                                val selected = if (current.useDefaultPostVisibility) defaultVisibility else current.selectedVisibility
                                current.copy(
                                    defaultPostVisibility = defaultVisibility,
                                    selectedVisibility = selected,
                                    isVisibilityReady = true
                                )
                            }
                        }
                    },
                    onFailure = {
                        if (privacyDefaultsGeneration == generation && isCurrentOwner(ownerUserId)) {
                            _uiState.update { it.copy(isVisibilityReady = true) }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (privacyDefaultsGeneration == generation && isCurrentOwner(ownerUserId)) {
                    _uiState.update { it.copy(isVisibilityReady = true) }
                }
            }
        }
        privacyDefaultsJob = job
    }

    fun ExploreOrchestrator.refresh() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        val session = feedController.currentSession()
        if (session == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = text(R.string.explore_login_required)
                )
            }
            return
        }
        val generation = ++feedGeneration
        refreshJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val job = scope.launch {
            try {
                val result = feedController.load(session, cursor = null)
                if (feedGeneration != generation || !feedController.isCurrent(session)) return@launch
                result.fold(
                    onSuccess = { posts ->
                        _uiState.update {
                            it.copy(
                                posts = posts,
                                isLoading = false,
                                hasMore = posts.size >= ExplorePaging.FEED_PAGE_SIZE,
                                errorMessage = null,
                                feedErrorMessage = null
                            )
                        }
                    },
                    onFailure = { error ->
                        val msg = error.message ?: text(R.string.explore_posts_load_failed)
                        _uiState.update { current ->
                            current.copy(
                                isLoading = false,
                                errorMessage = msg,
                                feedErrorMessage = if (current.posts.isEmpty()) msg else current.feedErrorMessage
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                if (feedGeneration == generation && feedController.isCurrent(session)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw e
            }
        }
        refreshJob = job
    }

    fun ExploreOrchestrator.loadMore() {
        val snapshot = _uiState.value
        if (snapshot.isLoading || snapshot.isLoadingMore || !snapshot.hasMore) return
        val lastPost = snapshot.posts.lastOrNull() ?: return
        val session = feedController.currentSession() ?: return
        val generation = feedGeneration
        val cursor = ExploreFeedPolicy.Cursor(createdAt = lastPost.createdAt, postId = lastPost.id)
        scope.launch {
            loadMoreMutex.withLock {
                if (feedGeneration != generation || !feedController.isCurrent(session)) return@withLock
                _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                try {
                    val result = feedController.load(session, cursor = cursor)
                    if (feedGeneration != generation || !feedController.isCurrent(session)) return@withLock
                    result.fold(
                        onSuccess = { newPosts ->
                            _uiState.update { current ->
                                val combined = (current.posts + newPosts).distinctBy { it.id }
                                current.copy(
                                    posts = combined,
                                    isLoadingMore = false,
                                    hasMore = newPosts.size >= ExplorePaging.FEED_PAGE_SIZE
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isLoadingMore = false,
                                    errorMessage = error.message ?: text(R.string.explore_load_more_failed)
                                )
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    if (feedGeneration == generation && feedController.isCurrent(session)) {
                        _uiState.update { it.copy(isLoadingMore = false) }
                    }
                    throw e
                }
            }
        }
    }
