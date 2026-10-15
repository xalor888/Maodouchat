package com.maodouchat.ui.screen.chatdetail

data class Templates(
    val youNudged: (targetName: String) -> String,
    val theyNudgedYou: (senderName: String) -> String,
    val theyNudgedTarget: (senderName: String, targetName: String) -> String
)
