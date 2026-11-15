package com.maodouchat.domain.contacts

// 用户名编辑纯规则：输入框过滤与保存前校验，不依赖 Android。
// 自设置页用户名控制器与资料对话框两处逐字相同的行内逻辑收敛；
// 错误文案仍在 app 侧按校验结果映射 R.string。
object UsernamePolicy {

    const val MIN_LENGTH: Int = 3
    const val MAX_LENGTH: Int = 50

    sealed interface Validation {
        data object Valid : Validation
        data object TooShort : Validation
        data object InvalidCharacters : Validation
    }

    // 输入过滤：只留字母/数字/下划线/连字符，截 50 字符，转小写，去掉开头的 @。
    fun filterInput(value: String): String =
        value.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }
            .take(MAX_LENGTH).lowercase().removePrefix("@")

    // 保存前校验：调用方先 trim；先判长度再判字符集，与原控制器顺序一致。
    fun validate(username: String): Validation = when {
        username.length < MIN_LENGTH -> Validation.TooShort
        !username.all { it.isLetterOrDigit() || it == '_' || it == '-' } ->
            Validation.InvalidCharacters
        else -> Validation.Valid
    }
}
