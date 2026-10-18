package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.runtime.Composable
import com.maodouchat.ai.AiEntryPolicy
import com.maodouchat.ai.AiPrivacyPreferences
import com.maodouchat.util.RuntimeFlags

@Composable
internal fun rememberChatDetailAiSurfaceVisibility(aiEnabled: Boolean, context: Context): Boolean =
    AiEntryPolicy.shouldShowAiSurfaces(
        chatAiEnabled = aiEnabled,
        consentAccepted = AiPrivacyPreferences.consentAccepted(context),
        userEnabled = AiPrivacyPreferences.userEnabled(context),
        masterEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)
    )
