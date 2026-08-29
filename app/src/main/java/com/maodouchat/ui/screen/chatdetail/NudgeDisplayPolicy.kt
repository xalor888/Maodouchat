package com.maodouchat.ui.screen.chatdetail

/**
 * The encrypted NUDGE body is sender-centric (`你拍了拍{target}`). Recipients rewrite it for
 * their local point of view after the v2 Inbox decrypts the message.
 */
object NudgeDisplayPolicy {

    private val youNudgedPrefixZh = "你拍了拍"
    private val youNudgedPrefixEn = "You nudged "

    data class Templates(
        val youNudged: (targetName: String) -> String,
        val theyNudgedYou: (senderName: String) -> String,
        val theyNudgedTarget: (senderName: String, targetName: String) -> String
    )

    /**
     * @param isOwnMessage true when local user is the nudge sender
     * @param storedContent decrypted body (usually "你拍了拍X")
     * @param senderDisplayName resolved peer/group member name for non-own display
     * @param isDirectChat when true, non-own copy becomes "{sender} 拍了拍你"
     */
    fun displayText(
        isOwnMessage: Boolean,
        storedContent: String,
        senderDisplayName: String,
        isDirectChat: Boolean,
        templates: Templates
    ): String {
        val target = extractTargetName(storedContent).ifBlank { storedContent.trim() }
        if (isOwnMessage) {
            return if (target.isNotBlank()) templates.youNudged(target) else storedContent
        }
        val sender = senderDisplayName.trim().ifBlank { "…" }
        return if (isDirectChat) {
            templates.theyNudgedYou(sender)
        } else {
            val t = target.ifBlank { "…" }
            templates.theyNudgedTarget(sender, t)
        }
    }

    fun extractTargetName(storedContent: String): String {
        val c = storedContent.trim()
        return when {
            c.startsWith(youNudgedPrefixZh) -> c.removePrefix(youNudgedPrefixZh).trim()
            c.startsWith(youNudgedPrefixEn, ignoreCase = true) ->
                c.substring(youNudgedPrefixEn.length).trim()
            else -> ""
        }
    }
}
