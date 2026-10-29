package com.maodouchat.util

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    @Test
    fun markOpenedKeepsEveryOtherMetaFieldIntact() {
        // markOpened 只翻 viewOnceOpened 一位，其余 meta 字段（回复/转文字/附件名）原样不动
        val meta = MessageMeta(
            viewOnce = true, viewOnceOpened = false,
            replyToId = "r1", voiceTranscript = "hello", fileName = "photo.jpg",
            fileMimeType = "image/jpeg"
        )
        val m = msg(type = MessageType.IMAGE).copy(
            content = com.maodouchat.util.JsonFormat.composeContentWithMeta("x", meta)
        )
        val marked = ViewOncePolicy.markOpened(m)
        val after = marked.parsedMeta()
        assertTrue(after.viewOnceOpened)
        assertEquals("r1", after.replyToId)
        assertEquals("hello", after.voiceTranscript)
        assertEquals("photo.jpg", after.fileName)
        assertEquals("image/jpeg", after.fileMimeType)
        assertEquals("x", marked.parsedContent(), "正文也不应被改动")
    }

    @Test
    fun messagesWithoutAMetaBlockAreInert() {
        // content 里没有 <meta> 块：机制整体不生效，markOpened 是严格 no-op
        val m = Message(
            id = "m9", chatId = "c1", senderId = "peer",
            content = "plain photo text", type = MessageType.IMAGE, timestamp = 1L
        )
        assertFalse(ViewOncePolicy.isViewOnce(m))
        assertFalse(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = false))
        assertEquals(m, ViewOncePolicy.markOpened(m))
    }

    @Test
    fun openedFlagWithoutTheViewOnceFlagIsInert() {
        // 只有 opened、没有 viewOnce 标记：异常组合，不锁也不翻转
        val m = msg(type = MessageType.IMAGE, viewOnce = false, opened = true)
        assertFalse(ViewOncePolicy.isViewOnce(m))
        assertFalse(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = false))
        assertEquals(m, ViewOncePolicy.markOpened(m))
    }

    @Test
    fun repeatedViewerChecksAreDeterministic() {
        // 重复判定不得改变消息对象，也不得出现抖动
        val m = msg(type = MessageType.IMAGE, viewOnce = true, opened = true)
        val snapshot = m
        repeat(3) {
            assertTrue(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = false))
        }
        assertEquals(snapshot, m)
    }

    @Test
    fun senderSeesViewOnceMessageBeforeAndAfterOpening() {
        // 发送者：未打开时能看；对方打开后（markOpened）自己依然能看——own 视角永不锁
        val fresh = msg(type = MessageType.IMAGE, viewOnce = true, opened = false, own = true)
        assertTrue(ViewOncePolicy.isViewOnce(fresh))
        assertFalse(ViewOncePolicy.isLockedForViewer(fresh, isOwnMessage = true))
        val opened = ViewOncePolicy.markOpened(fresh)
        assertFalse(ViewOncePolicy.isLockedForViewer(opened, isOwnMessage = true),
            "发送者视角永不锁，即使元数据已记打开")
    }

    @Test
    fun videoAndGifLockParityWithImage() {
        listOf(MessageType.VIDEO, MessageType.GIF).forEach { type ->
            val fresh = msg(type = type, viewOnce = true, opened = false)
            assertFalse(ViewOncePolicy.isLockedForViewer(fresh, isOwnMessage = false))
            val opened = ViewOncePolicy.markOpened(fresh)
            assertTrue(ViewOncePolicy.isLockedForViewer(opened, isOwnMessage = false))
        }
    }

    @Test
    fun markOpenedPreservesNonMetaFields() {
        val m = msg(type = MessageType.IMAGE, viewOnce = true, opened = false)
        val marked = ViewOncePolicy.markOpened(m)
        assertEquals(m.id, marked.id)
        assertEquals(m.chatId, marked.chatId)
        assertEquals(m.senderId, marked.senderId)
        assertEquals(m.type, marked.type)
        assertEquals(m.timestamp, marked.timestamp)
        assertEquals("x", marked.parsedContent())
    }

    @Test
    fun openedFlagOnUnsupportedTypeNeverLocks() {
        val text = msg(type = MessageType.TEXT, viewOnce = true, opened = true)
        assertFalse(ViewOncePolicy.isLockedForViewer(text, isOwnMessage = false))
        val file = msg(type = MessageType.FILE, viewOnce = true, opened = true)
        assertFalse(ViewOncePolicy.isLockedForViewer(file, isOwnMessage = false))
    }

    @Test
    fun senderNeverLockedForVideoAndGifEither() {
        listOf(MessageType.VIDEO, MessageType.GIF).forEach { type ->
            val m = msg(type = type, viewOnce = true, opened = true, own = true)
            assertFalse(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = true))
        }
    }

    @Test
    fun markingOneMessageLeavesSiblingUntouched() {
        val first = msg(type = MessageType.IMAGE, viewOnce = true, opened = false)
        val second = msg(type = MessageType.IMAGE, viewOnce = true, opened = false)
        ViewOncePolicy.markOpened(first)
        assertFalse(ViewOncePolicy.isLockedForViewer(second, isOwnMessage = false))
    }


    @Test
    fun supportsSweepIsExactlyImageVideoGif() {
        val supported = MessageType.entries.filter { ViewOncePolicy.supports(it) }.toSet()
        assertEquals(setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.GIF), supported)
    }

    @Test
    fun unsupportedTypesStayInertEvenWhenFlaggedAndOpened() {
        listOf(
            MessageType.MARKDOWN, MessageType.STICKER, MessageType.NUDGE,
            MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED
        ).forEach { type ->
            val m = msg(type = type, viewOnce = true, opened = true)
            assertFalse(ViewOncePolicy.isViewOnce(m), "$type 不应生效")
            assertFalse(ViewOncePolicy.isLockedForViewer(m, isOwnMessage = false), "$type 不应锁")
            assertSame(m, ViewOncePolicy.markOpened(m), "$type 的 markOpened 应原样返回")
        }
    }

    @Test
    fun videoAndGifAreViewOnceAtFlagLevel() {
        listOf(MessageType.VIDEO, MessageType.GIF).forEach { type ->
            assertTrue(ViewOncePolicy.isViewOnce(msg(type = type, viewOnce = true)))
            assertFalse(ViewOncePolicy.isViewOnce(msg(type = type, viewOnce = false)))
        }
    }

    @Test
    fun lockFollowsOwnFlagNotSenderId() {
        val ownOpened = msg(type = MessageType.IMAGE, viewOnce = true, opened = true, own = true)
        assertTrue(ViewOncePolicy.isLockedForViewer(ownOpened, isOwnMessage = false),
            "senderId 是自己也不影响：按 isOwnMessage=false 算就是接收者视角，应锁")
        val peerOpened = msg(type = MessageType.IMAGE, viewOnce = true, opened = true, own = false)
        assertFalse(ViewOncePolicy.isLockedForViewer(peerOpened, isOwnMessage = true),
            "senderId 是对方也不影响：按 isOwnMessage=true 算就是发送者视角，永不锁")
    }

    @Test
    fun markOpenedReturnsSameInstanceForInertMessages() {
        val alreadyOpened = msg(type = MessageType.IMAGE, viewOnce = true, opened = true)
        assertSame(alreadyOpened, ViewOncePolicy.markOpened(alreadyOpened))
        val plain = msg(type = MessageType.IMAGE, viewOnce = false, opened = false)
        assertSame(plain, ViewOncePolicy.markOpened(plain))
        val noMeta = Message(
            id = "m9", chatId = "c1", senderId = "peer",
            content = "plain photo text", type = MessageType.IMAGE, timestamp = 1L
        )
        assertSame(noMeta, ViewOncePolicy.markOpened(noMeta))
    }

}
