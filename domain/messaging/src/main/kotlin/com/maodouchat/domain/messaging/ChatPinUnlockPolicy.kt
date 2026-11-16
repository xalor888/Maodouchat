package com.maodouchat.domain.messaging

import kotlinx.coroutines.CancellationException

// 四处聊天锁 PIN 解锁的共享骨架：自 ChatDetailLock、AiTasksViewModel、
// MediaCenterScreen、StarredMessagesViewModel 的 unlock*WithPin 平移。
// 协程启动、IO 调度与解锁后的状态刷新仍在各 ViewModel；这里只收校验本身。
object ChatPinUnlockPolicy {

    const val MAX_PIN_LENGTH: Int = 8

    // 输入框草稿过滤：只留数字、截 8 位。
    fun filterPinDraft(raw: String): String =
        raw.filter { it.isDigit() }.take(MAX_PIN_LENGTH)

    // 与 setChatLockPin 的校验一致：4–8 位纯数字。
    fun isValidPin(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it.isDigit() }

    // 活跃会话 id 为空时回落到当前会话 id。
    fun resolveLockChatId(activeChatId: String, chatId: String): String =
        activeChatId.ifBlank { chatId }

    // 空会话 id 视为无需校验：不调 verify、不跑 onUnlocked，直接返回 true。
    // verify 抛取消异常时原样抛出，其余异常一律判校验失败。
    // onUnlocked 是挂起回调：MediaCenter 解锁后要解析会话名（resolveChatName 是挂起函数）。
    suspend fun unlock(
        chatId: String,
        pin: String,
        verify: suspend (chatId: String, pin: String) -> Boolean,
        onUnlocked: suspend () -> Unit,
    ): Boolean {
        if (chatId.isBlank()) return true
        val ok = try {
            verify(chatId, pin)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) onUnlocked()
        return ok
    }
}
