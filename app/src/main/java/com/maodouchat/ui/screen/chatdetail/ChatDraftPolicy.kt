package com.maodouchat.ui.screen.chatdetail

/**
 * Draft save scheduling decisions (pure; §6 extract from ChatDetailViewModel).
 */
object ChatDraftPolicy {
    const val SAVE_DELAY_MS = 350L

    fun canSchedule(ownerUserId: String?, chatId: String?): Boolean =
        !ownerUserId.isNullOrBlank() && !chatId.isNullOrBlank()

    fun shouldPersistGeneration(scheduled: Long, current: Long): Boolean =
        scheduled == current

    fun shouldWrite(ownerUserId: String?, liveUserId: String?): Boolean =
        !ownerUserId.isNullOrBlank() && ownerUserId == liveUserId

    /** Blank input clears stored draft rather than writing empty row noise. */
    fun isClearRequest(text: String): Boolean = text.isBlank()

    /**
     * Restoring a draft must not clobber an in-progress edit or non-empty field.
     */
    fun shouldApplyRestoredDraft(
        hasUserEditedInput: Boolean,
        currentInput: String
    ): Boolean = !hasUserEditedInput && currentInput.isBlank()
}
