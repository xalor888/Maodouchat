package com.maodouchat.core.crypto

// 解密失败占位符判定已下移至 :core:model（纯字符串匹配，与密码学原语无关）。
// 留 typealias 过渡：app 侧与 :core:crypto 内调用方暂不动，后续轮次拆除。
typealias DecryptPlaceholderPolicy = com.maodouchat.core.model.DecryptPlaceholderPolicy
