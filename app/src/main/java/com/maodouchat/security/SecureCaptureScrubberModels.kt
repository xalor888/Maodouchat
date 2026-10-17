package com.maodouchat.security

import android.net.Uri

internal data class Hit(
    val uri: Uri,
    val name: String,
    val path: String,
    val dateAdded: Long,
)
