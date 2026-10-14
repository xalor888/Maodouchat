package com.maodouchat.ui.screen.chatdetail

/**
 * 解密失败相关的界面文案（G328c 注入化）。
 *
 * 为什么要传进来而不是在类里调 `context.getString`：原先这些判定是
 * `ChatDetailViewModel` 的成员，取文案要经 `getApplication()`，
 * 于是 `ChatDetailDecryptPolicyTest` 的注释里只能写「本机无 Robolectric 测不了」。
 * 注入之后同样的逻辑在纯 JVM 里可测（见 `ChatDetailDecryptStatusTest`）。
 */
internal data class DecryptTexts(
    val failed: String,
    val pending: String,
    val sessionMissing: String,
    val identityChanged: String,
    val groupFailed: String,
    val groupKeyMissing: String,
    val groupIdentityChanged: String,
    val groupNewer: String,
    val imageFailed: String,
    val gifFailed: String,
    val stickerFailed: String,
    val locationFailed: String,
    val videoFailed: String,
    val voiceFailed: String,
    val fileFailed: String,
)
