package com.maodouchat.ai

import android.content.Context

/** AI 入口信息架构：薄门面，逻辑已按簇拆入 AiEntryVisibility / AiContextActionPolicy。 */
object AiEntryPolicy {

    const val PRIMARY_SURFACE = AiEntryVisibility.PRIMARY_SURFACE
    const val CONTEXT_SURFACE = AiEntryVisibility.CONTEXT_SURFACE
    const val SETTINGS_SURFACE = AiEntryVisibility.SETTINGS_SURFACE
    val COMPOSER_SECTION_ORDER: List<ComposerSection> = AiEntryVisibility.COMPOSER_SECTION_ORDER

    typealias ComposerSection = AiEntryVisibility.ComposerSection
    typealias MessageAiAction = AiContextActionPolicy.MessageAiAction

    fun shouldShowAiSurfaces(
        chatAiEnabled: Boolean,
        consentAccepted: Boolean,
        userEnabled: Boolean = false,
        masterEnabled: Boolean = true
    ): Boolean = AiEntryVisibility.shouldShowAiSurfaces(
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
        AiContextActionPolicy.contextActionsFor(messageType, hasTranscript)

    fun hasContextAiActions(
        messageType: String?,
        hasTranscript: Boolean = false
    ): Boolean = AiContextActionPolicy.hasContextAiActions(messageType, hasTranscript)

    fun isComposerEntryActive(chatAiEnabled: Boolean): Boolean =
        AiEntryVisibility.isComposerEntryActive(chatAiEnabled)

    fun isComposerEntryActive(context: Context, chatAiEnabled: Boolean): Boolean =
        AiEntryVisibility.isComposerEntryActive(context, chatAiEnabled)

    fun canOpenComposerMenu(isBusy: Boolean, isUpdatingSetting: Boolean = false): Boolean =
        AiContextActionPolicy.canOpenComposerMenu(isBusy, isUpdatingSetting)

    fun canRunContextAction(
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = AiContextActionPolicy.canRunContextAction(chatAiEnabled, isBusy)

    fun canRunContextAction(
        context: Context,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = AiContextActionPolicy.canRunContextAction(context, chatAiEnabled, isBusy)

    fun canRunContextAction(
        masterEnabled: Boolean,
        chatAiEnabled: Boolean,
        isBusy: Boolean
    ): Boolean = AiContextActionPolicy.canRunContextAction(masterEnabled, chatAiEnabled, isBusy)
}
