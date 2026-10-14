package com.maodouchat.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.ai.AiPrivacyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

class AiPrivacySettingsViewModel(application: Application) : AndroidViewModel(application) {
    internal val writingStylePushMutex = Mutex()
    internal var writingStylePushGeneration = 0L
    internal var refreshJob: kotlinx.coroutines.Job? = null
    internal var refreshGeneration = 0L
    internal val aiMutationMutex = Mutex()
    internal var pendingAiMutationKey: String? = null
    internal var writingStyleRevision = 0L
    internal var aiSettingsRevision = 0L

    internal val _uiState = MutableStateFlow(
        AiPrivacySettingsUiState(
            userEnabled = AiPrivacyPreferences.userEnabled(application),
            effectiveEnabled = AiPrivacyPreferences.userEnabled(application) &&
                AiPrivacyPreferences.consentAccepted(application),
            aiConsentAccepted = AiPrivacyPreferences.consentAccepted(application),
            localSafetyEnabled = AiPrivacyPreferences.localSafetyEnabled(application)
        )
    )
    val uiState: StateFlow<AiPrivacySettingsUiState> = _uiState.asStateFlow()

    init {
        val style = loadWritingStyleSnapshot()
        _uiState.update {
            it.copy(
                writingStyleEnabled = style.enabled,
                writingStylePresetId = style.preset.id,
                writingStyleCustomNote = style.customNote
            )
        }
        refresh()
    }
}
