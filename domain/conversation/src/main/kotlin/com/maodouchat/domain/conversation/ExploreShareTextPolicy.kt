package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto

// 动态分享文案：正文加引号、附 [图片] 标记与作者公开主页链接；
// 收敛动态流与动态详情页两处逐字相同的分享 buildString。
// 服务器地址由调用方传入（ApiConfig.BASE_URL，跟随当前服务器地址），
// 策略只收纯字符串逻辑；空文案回退为 "动态"。
object ExploreShareTextPolicy {

    fun buildShareText(post: PostDto, baseUrl: String): String = buildString {
        append(post.content.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "")
        if (post.imageUrls.isNotEmpty()) {
            if (isNotBlank()) append("\n")
            append("[图片]")
        }
        val authorUsername = post.author.username?.takeIf { it.isNotBlank() }
        if (authorUsername != null) {
            if (isNotBlank()) append("\n")
            append(baseUrl.trimEnd('/')).append("/u/").append(authorUsername)
        }
    }.ifBlank { "动态" }
}
