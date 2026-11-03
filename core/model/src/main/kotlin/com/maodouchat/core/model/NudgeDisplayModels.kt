package com.maodouchat.core.model

data class Templates(
    val youNudged: (targetName: String) -> String,
    val theyNudgedYou: (senderName: String) -> String,
    val theyNudgedTarget: (senderName: String, targetName: String) -> String
)
