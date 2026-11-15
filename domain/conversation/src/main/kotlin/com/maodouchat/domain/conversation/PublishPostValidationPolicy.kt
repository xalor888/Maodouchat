package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PublishPostValidation

// 发布动态校验：正文 trim 后为空且无图片不合法；正文超过 5000 字不合法。
// 自 PublishPostUseCase.validate 逐字平移。
object PublishPostValidationPolicy {

    fun validate(content: String, imageUrls: List<String>): PublishPostValidation {
        val trimmed = content.trim()
        if (trimmed.isEmpty() && imageUrls.isEmpty()) {
            return PublishPostValidation.Invalid("Post content or image cannot be empty")
        }
        if (trimmed.length > 5000) {
            return PublishPostValidation.Invalid("Post content exceeds 5000 characters")
        }
        return PublishPostValidation.Valid
    }
}
