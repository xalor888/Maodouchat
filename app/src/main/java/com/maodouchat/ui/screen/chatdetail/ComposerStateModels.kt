package com.maodouchat.ui.screen.chatdetail

internal data class ComposerStateSnapshot(
    val attachMenuVisible: Boolean,
    val expressionPanelVisible: Boolean,
    val expressionMode: String,
    val aiMenuVisible: Boolean,
    val translationLanguagesVisible: Boolean,
    val quickPhrasesVisible: Boolean,
    val contactCardPickerVisible: Boolean,
)
