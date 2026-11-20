package com.maodouchat.domain.messaging

/**
 * 聊天 Markdown 子集的**纯文本处理**（转纯文本）。
 *
 * G328c：这个 `object` 原先住在 `ui/component/MarkdownMessage.kt` 里（同文件还有一个
 * `@Composable MarkdownMessageContent`），于是 `notification/MessageNotificationService`
 * 为了 `stripContactCardMarker` 一行被迫 import `com.maodouchat.messaging.ChatMarkdown`——
 * 通知服务依赖 UI 包属于分层倒置。这里只把**不含 Compose 依赖**的那部分抽出来；
 * 渲染用的 Composable 仍留在 ui/component。
 */
object ChatMarkdown {
    // toPlainText 的逐行 strip 模式：每次调用重复编译，提到对象级复用。
    private val STRIP_IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val STRIP_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val STRIP_BOLD_STAR_REGEX = Regex("\\*\\*(.+?)\\*\\*")
    private val STRIP_BOLD_UNDER_REGEX = Regex("__(.+?)__")
    private val STRIP_STRIKE_REGEX = Regex("~~(.+?)~~")
    private val STRIP_CODE_REGEX = Regex("`([^`]+)`")
    private val STRIP_LINE_START_REGEX = Regex("(?m)^\\s{0,3}(#{1,6}[ \\t]+|> ?|[-+*] ?|\\d+\\. ?|```+)")


    /** 0.71：剥离常见 Markdown 语法得到纯文本（「复制为纯文本」用）。 */
    fun toPlainText(markdown: String): String {
        var text = markdown
        // [t](url) -> t（9.230：必须先于行首语法剥离——否则 `- [t](url)` 的 `- ` 前缀
        // 被先行删去后无碍，但图片 ![alt](url) 的 ! 残留及顺序交换引发的链接断裂已规避）
        text = text.replace(STRIP_IMAGE_REGEX, "$1")
        text = text.replace(STRIP_LINK_REGEX, "$1")
        // 9.230：内联语法必须先于行首剥离——行首规则中的 ```+ 会吞掉行内代码的
        // 首个反引号，导致孤儿反引号残留进复制/分享文本
        text = text.replace(STRIP_BOLD_STAR_REGEX, "$1")
        text = text.replace(STRIP_BOLD_UNDER_REGEX, "$1")
        text = text.replace(STRIP_STRIKE_REGEX, "$1")
        text = text.replace(STRIP_CODE_REGEX, "$1")
        // 行首语法：标题/引用/无序/有序列表/代码围栏
        text = text.replace(STRIP_LINE_START_REGEX, "")
        return text.trim()
    }
}
