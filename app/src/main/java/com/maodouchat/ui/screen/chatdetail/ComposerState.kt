package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

@Stable
internal class ComposerState private constructor(
    attachMenuVisible: Boolean,
    expressionPanelVisible: Boolean,
    expressionModeValue: String,
    aiMenuVisible: Boolean,
    translationLanguagesVisible: Boolean,
    quickPhrasesVisible: Boolean,
    contactCardPickerVisible: Boolean,
) {
    val attachMenu: MutableState<Boolean> = mutableStateOf(attachMenuVisible)
    val expressionPanel: MutableState<Boolean> = mutableStateOf(expressionPanelVisible)
    val expressionMode: MutableState<String> = mutableStateOf(expressionModeValue)
    val aiMenu: MutableState<Boolean> = mutableStateOf(aiMenuVisible)
    val translationLanguages: MutableState<Boolean> = mutableStateOf(translationLanguagesVisible)
    val quickPhrases: MutableState<Boolean> = mutableStateOf(quickPhrasesVisible)
    val contactCardPicker: MutableState<Boolean> = mutableStateOf(contactCardPickerVisible)

    constructor() : this(
        attachMenuVisible = false,
        expressionPanelVisible = false,
        expressionModeValue = EXPRESSION_MODE_EMOJI,
        aiMenuVisible = false,
        translationLanguagesVisible = false,
        quickPhrasesVisible = false,
        contactCardPickerVisible = false,
    )

    fun toggleAttachMenu() {
        val show = !attachMenu.value
        dismissTransientPanels()
        attachMenu.value = show
    }

    fun toggleExpressionPanel() {
        val show = !expressionPanel.value
        dismissTransientPanels()
        expressionPanel.value = show
    }

    fun showAiMenu() {
        dismissTransientPanels()
        aiMenu.value = true
    }

    fun openQuickPhrases() {
        dismissTransientPanels()
        quickPhrases.value = true
    }

    fun openContactCardPicker() {
        dismissTransientPanels()
        contactCardPicker.value = true
    }

    fun dismissTopPanel(): Boolean = when {
        aiMenu.value -> aiMenu.setFalse()
        quickPhrases.value -> quickPhrases.setFalse()
        attachMenu.value -> attachMenu.setFalse()
        expressionPanel.value -> expressionPanel.setFalse()
        else -> false
    }

    fun dismissTransientPanels() {
        attachMenu.value = false
        expressionPanel.value = false
        aiMenu.value = false
        quickPhrases.value = false
    }

    internal fun snapshot() = ComposerStateSnapshot(
        attachMenuVisible = attachMenu.value,
        expressionPanelVisible = expressionPanel.value,
        expressionMode = expressionMode.value,
        aiMenuVisible = aiMenu.value,
        translationLanguagesVisible = translationLanguages.value,
        quickPhrasesVisible = quickPhrases.value,
        contactCardPickerVisible = contactCardPicker.value,
    )

    private fun MutableState<Boolean>.setFalse(): Boolean {
        value = false
        return true
    }

    companion object {
        const val EXPRESSION_MODE_EMOJI = "EMOJI"

        val Saver: Saver<ComposerState, List<Any>> = Saver(
            save = { state ->
                state.snapshot().let { snapshot ->
                    listOf(
                        snapshot.attachMenuVisible,
                        snapshot.expressionPanelVisible,
                        snapshot.expressionMode,
                        snapshot.aiMenuVisible,
                        snapshot.translationLanguagesVisible,
                        snapshot.quickPhrasesVisible,
                        snapshot.contactCardPickerVisible,
                    )
                }
            },
            restore = { values ->
                ComposerState(
                    attachMenuVisible = values[0] as Boolean,
                    expressionPanelVisible = values[1] as Boolean,
                    expressionModeValue = values[2] as String,
                    aiMenuVisible = values[3] as Boolean,
                    translationLanguagesVisible = values[4] as Boolean,
                    quickPhrasesVisible = values[5] as Boolean,
                    contactCardPickerVisible = values[6] as Boolean,
                )
            },
        )
    }
}

internal data class ComposerStateSnapshot(
    val attachMenuVisible: Boolean,
    val expressionPanelVisible: Boolean,
    val expressionMode: String,
    val aiMenuVisible: Boolean,
    val translationLanguagesVisible: Boolean,
    val quickPhrasesVisible: Boolean,
    val contactCardPickerVisible: Boolean,
)

@Composable
internal fun rememberComposerState(): ComposerState = rememberSaveable(saver = ComposerState.Saver) {
    ComposerState()
}
