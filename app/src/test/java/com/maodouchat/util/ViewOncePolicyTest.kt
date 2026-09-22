package com.maodouchat.util

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G210b：`ViewOncePolicy` —— 阅后即焚（看一次）媒体的可见性判定。
 *
 * 它被 6 个文件引用，KDoc 写着「纯函数，可单测」，零测试。
 * 这里守的是**隐私**而不是功能：
 *
 * - **发送者永远能看自己的**（`isOwnMessage` 不锁）——否则自己发完就再也打不开；
 * - **没打开过的接收者能看**——否则消息发出去就是坏的；
 * - **打开过的接收者不能再开**（`isLockedForViewer`）——这是阅后即焚的本体；
 * - **类型不支持时整个机制不生效**（`supports`）——比如给文本消息打上 viewOnce
 *   标记不该让文本消失，因为 KDoc 明确限定「1:1 的图片/视频/GIF」。
 */
class ViewOncePolicyTest {

    private fun msg(
        type: MessageType = MessageType.IMAGE,
        viewOnce: Boolean = false,
        opened: Boolean = false,
        own: Boolean = false,
    ): Message {
        val meta = MessageMeta(viewOnce = viewOnce, viewOnceOpened = opened)
        val base = Message(
            id = "m1",
            chatId = "c1",
            senderId = if (own) "me" else "peer",
            content = com.maodouchat.util.JsonFormat.composeContentWithMeta("x", meta),
            type = type,
            timestamp = 1L,
        )
        return base
    }

    @Test
    fun onlySupportedTypesAreViewOnce() {
        // 支持集合：图片 / 视频 / GIF
        listOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF).forEach {
            assertTrue(ViewOncePolicy.supports(it), "$it 应支持阅后即焚")
        }
        // 其它类型不支持——尤其文本（给文本打 viewOnce 不该让文本消失）
        listOf(MessageType.TEXT, MessageType.FILE, MessageType.VOICE, MessageType.LOCATION)
            .forEach { assertFalse(ViewOncePolicy.supports(it), "$it 不应支持") }
    }

    @Test
    fun viewOnceRequiresBothTheFlagAndASupportedType() {
        assertTrue(ViewOncePolicy.isViewOnce(msg(type = MessageType.IMAGE, viewOnce = true)))
        // 有标记但类型不支持 → 不生效
        assertFalse(ViewOncePolicy.isViewOnce(msg(type = MessageType.TEXT, viewOnce = true)))
        // 类型支持但无标记 → 不生效
        assertFalse(ViewOncePolicy.isViewOnce(msg(type = MessageType.IMAGE, viewOnce = false)))
    }

    @Test
    fun senderCanAlwaysSeeTheirOwn() {
        val m = msg(type = MessageType.IMAGE, viewOnce = true, opened = true, own = true)
        assertTrue(ViewOncePolicy.isViewOnce(m), "标记本身应生效")
        assertFalse(
            ViewOncePolicy.isLockedForViewer(m, isOwnMessage = true),
            "发送者即使对方已打开也应能看自己的消息",
        )
    }

    @Test
    fun viewerCanSeeUntilTheyOpenIt() {
        val m = msg(type = MessageType.IMAGE, viewOnce = true, opened = false)
        assertFalse(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = false),
            "还没打开的接收者应能看")
    }

    @Test
    fun viewerIsLockedAfterOpening() {
        val m = msg(type = MessageType.IMAGE, viewOnce = true, opened = true)
        assertTrue(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = false),
            "打开过之后必须锁住——这是阅后即焚的本体")
        // 非阅后即焚的消息，打没打开都一样不该锁
        val plain = msg(type = MessageType.IMAGE, viewOnce = false, opened = true)
        assertFalse(ViewOncePolicy.isLockedForViewer(plain, isOwnMessage = false))
    }

    @Test
    fun markOpenedIsIdempotentAndOnlyAffectsViewOnce() {
        val fresh = msg(type = MessageType.IMAGE, viewOnce = true, opened = false)
        val once = ViewOncePolicy.markOpened(fresh)
        assertTrue(ViewOncePolicy.isViewOnce(once))
        assertTrue(once.parsedMeta().viewOnceOpened, "标记后应记录已打开")
        assertTrue(ViewOncePolicy.isLockedForViewer(once, isOwnMessage = false))

        // 幂等：再标记一次，结果不变（不重复写 meta）
        val twice = ViewOncePolicy.markOpened(once)
        assertEquals(once, twice, "markOpened 应幂等")

        // 非阅后即焚消息不该被改
        val plain = msg(type = MessageType.IMAGE, viewOnce = false, opened = false)
        assertEquals(plain, ViewOncePolicy.markOpened(plain), "非阅后即焚消息不应被改动")
        // 类型不支持的也不该被改（即使 meta 里 viewOnce=true）
        val textViewOnce = msg(type = MessageType.TEXT, viewOnce = true, opened = false)
        assertEquals(textViewOnce, ViewOncePolicy.markOpened(textViewOnce),
            "类型不支持时 markOpened 不该改动")
    }
}
