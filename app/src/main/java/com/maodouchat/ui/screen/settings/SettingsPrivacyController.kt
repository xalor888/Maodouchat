package com.maodouchat.ui.screen.settings

import com.maodouchat.R
import com.maodouchat.settings.model.LoadedPrivacy
import com.maodouchat.settings.model.PrivacyField
import com.maodouchat.settings.model.SettingsUiState
import com.maodouchat.settings.repository.SettingsPrivacyPatch
import com.maodouchat.settings.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * G360：隐私设置一族（加载 / 打开关闭 / 五个开关处理器 / 保存）从 `SettingsViewModel` 抽出
 * （纯搬移不改判断）——含 `trackPrivacyField` 骨架、`currentLoadedPrivacy`、`normalizeVisibility`；
 * 三个字段（privacySaveJob / loadedPrivacy / dirtyPrivacyFields）的所有权随之内聚。
 *
 * 依赖全经构造器注入：scope / 状态读写 / 文案 / 会话属主校验 / 设置仓库。
 */
internal class SettingsPrivacyController(
    private val scope: CoroutineScope,
    private val currentState: () -> SettingsUiState,
    private val updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val isCurrentOwner: (String) -> Boolean,
    private val settingsRepository: SettingsRepository,
) {
    private var privacySaveJob: Job? = null
    private var loadedPrivacy: LoadedPrivacy? = null
    private val dirtyPrivacyFields = mutableSetOf<PrivacyField>()

    private fun normalizeVisibility(value: String): String = com.maodouchat.settings.normalizeVisibility(value, "PUBLIC")

    /**
     * 隐私开关公共骨架：保存中直接忽略；与已加载值比对决定脏位增减；
     * 最后应用 UI 更新。调用方只给字段、是否干净与更新 lambda。
     */
    private inline fun trackPrivacyField(
        vararg fields: PrivacyField,
        isClean: () -> Boolean,
        update: () -> Unit,
    ) {
        if (currentState().isSavingPrivacy) return
        if (isClean()) {
            dirtyPrivacyFields -= fields.toSet()
        } else {
            dirtyPrivacyFields += fields.toSet()
        }
        update()
    }

    private fun currentLoadedPrivacy() = loadedPrivacy?.takeIf { it.ownerUserId == com.maodouchat.session.CurrentSession.snapshot().userId }

    internal fun loadPrivacy() {
        scope.launch {
            val session = settingsRepository.currentSession()
            if (session == null) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            if (loadedPrivacy?.ownerUserId != session.ownerUserId) {
                loadedPrivacy = null
                dirtyPrivacyFields.clear()
            }
            settingsRepository.loadPrivacy(session).fold(
                onSuccess = { privacy ->
                    if (!settingsRepository.isCurrent(session)) return@fold
                    val loaded = LoadedPrivacy(
                        ownerUserId = session.ownerUserId,
                        showOnline = privacy.showOnline,
                        showStatus = privacy.showStatus,
                        searchable = privacy.searchable,
                        defaultPostVisibility = normalizeVisibility(privacy.defaultPostVisibility),
                        onlineVisibility = privacy.onlineVisibility.ifBlank {
                            if (privacy.showOnline) "everyone" else "nobody"
                        },
                    )
                    loadedPrivacy = loaded
                    updateState { current ->
                        current.copy(
                            showOnline = if (PrivacyField.SHOW_ONLINE in dirtyPrivacyFields) current.showOnline else loaded.showOnline,
                            onlineVisibility = if (PrivacyField.ONLINE_VISIBILITY in dirtyPrivacyFields) current.onlineVisibility else loaded.onlineVisibility,
                            showStatus = if (PrivacyField.SHOW_STATUS in dirtyPrivacyFields) current.showStatus else loaded.showStatus,
                            searchable = if (PrivacyField.SEARCHABLE in dirtyPrivacyFields) current.searchable else loaded.searchable,
                            defaultPostVisibility = if (PrivacyField.DEFAULT_POST_VISIBILITY in dirtyPrivacyFields) {
                                current.defaultPostVisibility
                            } else {
                                loaded.defaultPostVisibility
                            },
                        )
                    }
                },
                onFailure = { error ->
                    if (!settingsRepository.isCurrent(session)) return@fold
                    updateState {
                        it.copy(errorMessage = error.message ?: text(R.string.settings_privacy_load_failed))
                    }
                },
            )
        }
    }

    fun openPrivacy() {
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (loadedPrivacy?.ownerUserId != ownerUserId) loadPrivacy()
        updateState { it.copy(showPrivacyDialog = true) }
    }

    fun closePrivacy() {
        if (currentState().isSavingPrivacy) return
        val baseline = loadedPrivacy?.takeIf { it.ownerUserId == com.maodouchat.session.CurrentSession.snapshot().userId }
        dirtyPrivacyFields.clear()
        updateState {
            it.copy(
                showPrivacyDialog = false,
                showOnline = baseline?.showOnline ?: it.showOnline,
                onlineVisibility = baseline?.onlineVisibility ?: it.onlineVisibility,
                showStatus = baseline?.showStatus ?: it.showStatus,
                searchable = baseline?.searchable ?: it.searchable,
                defaultPostVisibility = baseline?.defaultPostVisibility ?: it.defaultPostVisibility
            )
        }
    }

    fun onShowOnlineChange(v: Boolean) {
        if (currentState().isSavingPrivacy) return
        val vis = if (v) {
            currentState().onlineVisibility.takeUnless { it == "nobody" } ?: "everyone"
        } else {
            "nobody"
        }
        onOnlineVisibilityChange(vis)
    }

    fun onOnlineVisibilityChange(v: String) {
        val normalized = when (v) {
            "contacts", "nobody" -> v
            else -> "everyone"
        }
        trackPrivacyField(
            PrivacyField.ONLINE_VISIBILITY, PrivacyField.SHOW_ONLINE,
            isClean = { currentLoadedPrivacy()?.onlineVisibility == normalized },
        ) {
            updateState { it.copy(onlineVisibility = normalized, showOnline = normalized != "nobody") }
        }
    }

    fun onShowStatusChange(v: Boolean) {
        trackPrivacyField(
            PrivacyField.SHOW_STATUS,
            isClean = { currentLoadedPrivacy()?.showStatus == v },
        ) {
            updateState { it.copy(showStatus = v) }
        }
    }

    fun onSearchableChange(v: Boolean) {
        trackPrivacyField(
            PrivacyField.SEARCHABLE,
            isClean = { currentLoadedPrivacy()?.searchable == v },
        ) {
            updateState { it.copy(searchable = v) }
        }
    }

    fun onDefaultVisibilityChange(v: String) {
        val normalized = normalizeVisibility(v)
        trackPrivacyField(
            PrivacyField.DEFAULT_POST_VISIBILITY,
            isClean = { currentLoadedPrivacy()?.defaultPostVisibility == normalized },
        ) {
            updateState { it.copy(defaultPostVisibility = normalized) }
        }
    }

    fun savePrivacy() {
        if (privacySaveJob?.isActive == true) return
        privacySaveJob = scope.launch {
            val session = settingsRepository.currentSession()
            val privacyOwnerUserId = session?.ownerUserId.orEmpty()
            if (session == null) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            updateState { it.copy(isSavingPrivacy = true, errorMessage = null) }
            // 在 isSavingPrivacy=true 之后才快照，避免并发 toggle 导致保存旧状态
            val snapshot = currentState()
            val changedFields = dirtyPrivacyFields.toSet()
            if (changedFields.isEmpty()) {
                updateState { it.copy(isSavingPrivacy = false, showPrivacyDialog = false) }
                return@launch
            }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = privacyOwnerUserId,
                )
                ) {
                    return@launch
                }
                settingsRepository.savePrivacy(
                    session,
                    SettingsPrivacyPatch(
                        showOnline = snapshot.showOnline.takeIf { PrivacyField.SHOW_ONLINE in changedFields },
                        showStatus = snapshot.showStatus.takeIf { PrivacyField.SHOW_STATUS in changedFields },
                        searchable = snapshot.searchable.takeIf { PrivacyField.SEARCHABLE in changedFields },
                        defaultPostVisibility = snapshot.defaultPostVisibility.takeIf {
                            PrivacyField.DEFAULT_POST_VISIBILITY in changedFields
                        },
                        onlineVisibility = snapshot.onlineVisibility.takeIf {
                            PrivacyField.ONLINE_VISIBILITY in changedFields
                        },
                    ),
                ).fold(
                    onSuccess = { privacy ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = privacyOwnerUserId,
                        )
                        ) {
                            return@fold
                        }
                        val normalizedVisibility = normalizeVisibility(privacy.defaultPostVisibility)
                        loadedPrivacy = LoadedPrivacy(
                            ownerUserId = privacyOwnerUserId,
                            showOnline = privacy.showOnline,
                            showStatus = privacy.showStatus,
                            searchable = privacy.searchable,
                            defaultPostVisibility = normalizedVisibility,
                            onlineVisibility = privacy.onlineVisibility.ifBlank { if (privacy.showOnline) "everyone" else "nobody" }
                        )
                        dirtyPrivacyFields.clear()
                        updateState {
                            it.copy(
                                showOnline = privacy.showOnline,
                                onlineVisibility = privacy.onlineVisibility.ifBlank { if (privacy.showOnline) "everyone" else "nobody" },
                                showStatus = privacy.showStatus,
                                searchable = privacy.searchable,
                                defaultPostVisibility = normalizedVisibility,
                                showPrivacyDialog = false,
                                isSavingPrivacy = false,
                                successMessage = text(R.string.settings_privacy_saved)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(privacyOwnerUserId)) return@fold
                        updateState { it.copy(isSavingPrivacy = false, errorMessage = error.message ?: text(R.string.settings_privacy_save_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(privacyOwnerUserId)) {
                    updateState { it.copy(isSavingPrivacy = false) }
                }
                throw error
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
