package com.maodouchat.ui.screen.contacts

data class MyQrCodeUiState(
    val userName: String = "",
    val userId: String = "",
    val userAvatar: String? = null,
    val qrBitmap: android.graphics.Bitmap? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)
