package com.maodouchat.core.model

/** 应用级统一深链/系统入口路由器（纯 Kotlin，无 Android 依赖）：只做纯决策——解析、清洗、目标建模与路由字符串生成，不触碰 NavController / Activity / Token。 */

object AppLinkRouter {
    const val MAX_USERNAME_LENGTH = 64

    const val MAX_ID_LENGTH = 128

    /** 与服务端 `GroupInvitationRepository` / QR 载荷一致：Base64URL(32B)≈43，上限 80。 */
    const val MAX_INVITE_CODE_LENGTH = 80

    const val MIN_CHAT_INVITE_TOKEN_LENGTH = 32

    const val CHAT_INVITE_COLON_PREFIX = "maodouchat:chat-invite:v1:"

    const val MAX_USER_FACING_URL_LENGTH = 2048

    /**
     * 对外深链模式唯一事实源（公开资料页）。
     * - `NavGraph` 的 `navDeepLink` 列表直接由此生成；
     * - `AndroidManifest.xml` 的两个 intent-filter 与此逐项对应（XML 无法引用代码，
     *   改动时必须同步三处，见下注释）；
     * - `parseDeepLink` 只接受落在此白名单内的 scheme/host。
     */
    // Manifest 镜像：
    //   https + chat.mdou.me + pathPrefix /u/
    //   maodouchat + host u
    //   https + chat.mdou.me + pathPrefix /join/
    //   maodouchat + host invite
    val publicProfileDeepLinkPatterns: List<String> = listOf(
        "https://chat.mdou.me/u/{username}",
        "https://chat.mdou.me/u/{username}?embed={embed}",
        "maodouchat://u/{username}",
    )

    /** 群邀请深链模式（与 Manifest intent-filter / parseDeepLink 白名单同步）。 */
    val groupInviteDeepLinkPatterns: List<String> = listOf(
        "https://chat.mdou.me/join/{code}",
        "maodouchat://invite/{code}",
    )

}
