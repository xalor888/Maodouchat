package com.maodouchat.ai

import android.content.Context
import com.maodouchat.core.model.ComposerSection
import com.maodouchat.core.model.MessageAiAction
import com.maodouchat.domain.messaging.AiEntryVisibilityPolicy
import com.maodouchat.domain.messaging.MessageAiActionPolicy
import com.maodouchat.util.RuntimeFlags

/** AI 入口信息架构：薄门面，纯逻辑已收敛到 :domain:messaging 的新策略。 */
object AiEntryPolicy {

    const val PRIMARY_SURFACE = AiEntryVisibility.PRIMARY_SURFACE
    const val CONTEXT_SURFACE = AiEntryVisibility.CONTEXT_SURFACE
    const val SETTINGS_SURFACE = AiEntryVisibility.SETTINGS_SURFACE
    val COMPOSER_SECTION_ORDER: List<ComposerSection> = AiEntryVisibility.COMPOSER_SECTION_ORDER

    fun shouldShowAiSurfaces(
        chatAiEnabled: Boolean,
        consentAccepted: Boolean,
        userEnabled: Boolean = false,
        masterEnabled: Boolean = true
    ): Boolean = AiEntryVisibilityPolicy.shouldShowAiSurfaces(
        chatAiEnabled = chatAiEnabled,
        consentAccepted = consentAccepted,
        userEnabled = userEnabled,
        masterEnabled = masterEnabled
    )

    fun shouldShowGlobalAiEntry(context: Context): Boolean =
        AiEntryVisibility.shouldShowGlobalAiEntry(context)

    fun contextActionsFor(
        messageType: String?,
        hasTranscript: Boolean = false
    ): List<MessageAiAction> =
        MessageAiActionPolicy.contextActionsFor(messageType, hasTranscript)

    fun hasContextAiActions(
        messageType: String?,
        hasTranscript: Boolean = false
    ): Boolean = MessageAiActionPolicy.hasContextAiActions(messageType, hasTranscript)

    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean =
        AiEntryVisibilityPolicy.isComposerEntryActive(chatAiEnabled)

    fun isComposerEntryActive(context: Context, chatAiEnabled: Boolean): Boolean =
        AiEntryVisibility.isComposerEntryActive(context, chatAiEnabled)

    fun canOpenComposerMenu(isBusy: Boolean, isUpdatingSetting: Boolean = false): Boolean =
        MessageAiActionPolicy.canOpenComposerMenu(isBusy, isUpdatingSetting)

    fun canRunContextAction(
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = MessageAiActionPolicy.canRunContextAction(chatAiEnabled, isBusy)

    fun canRunContextAction(
        context: Context,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = MessageAiActionPolicy.canRunContextAction(
        masterEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER),
        chatAiEnabled = chatAiEnabled,
        isBusy = isBusy
    )

    fun canRunContextAction(
        masterEnabled: Boolean,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = MessageAiActionPolicy.canRunContextAction(masterEnabled, chatAiEnabled, isBusy)
}
