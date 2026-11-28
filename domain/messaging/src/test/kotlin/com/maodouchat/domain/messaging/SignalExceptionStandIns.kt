package com.maodouchat.domain.messaging

// Signal 库异常名的本地替身：生产侧只读 javaClass.simpleName 与 cause 链，
// 因此 simpleName 必须与真实类一致。声明为 internal 顶层类，供多个
// fuzz 用例共享（原先各文件各自声明 private 类，合并后 JVM 层同名冲突）。
internal class NoSessionException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class UntrustedIdentityException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class InvalidKeyIdException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class InvalidKeyException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal class StaleKeyException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
