from pathlib import Path

# ── 1) ChatDetailViewModel: 观察 WebSocket 事件（吞了 StatusChanged/Connected/Disconnected/Error）
p = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt')
s = p.read_text()

old_ws = '''    private fun observeWebSocket() {
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        // MessageReceived handling already exists above
                    }
                    else -> {}
                }
            }
        }
    }'''

new_ws = '''    private fun observeWebSocket() {
        viewModelScope.launch {
            WebSocketClient.events.collect { event ->
                when (event) {
                    is WebSocketEvent.MessageReceived -> {
                        // 异步解密；解密后写入 Room + 发送 DELIVERED 回执
                        val msg = withContext(Dispatchers.IO) {
                            decryptIncomingMessage(event.message.senderId, event.message)
                        } ?: return@collect
                        if (msg.chatId == activeChatId) {
                            upsertUiMessage(msg)
                            withContext(Dispatchers.IO) { messageRepo.insertMessage(msg) }
                            WebSocketClient.sendStatusUpdate(msg.id, MessageStatus.DELIVERED)
                        }
                    }
                    is WebSocketEvent.StatusChanged -> updateMessageStatus(event.messageId, event.status)
                    is WebSocketEvent.Connected -> if (event.success) syncMissedMessages()
                    is WebSocketEvent.Disconnected -> Unit  // UI 不感知，等 Connected 时同步
                    is WebSocketEvent.Error -> android.util.Log.w(TAG, "WebSocket error: ${event.error}")
                    else -> Unit
                }
            }
        }
    }

    // 重连后同步断连期间的消息
    private suspend fun syncMissedMessages() {
        if (activeChatId.isBlank() || activeChatId == "c1") return
        val latest = _uiState.value.messages.lastOrNull()?.timestamp ?: 0
        val result = ApiService.getMessages(token, activeChatId, limit = 100)
        result.onSuccess { dtos ->
            if (dtos.isNotEmpty()) {
                val newMessages = withContext(Dispatchers.IO) {
                    dtos.filter { it.timestamp > latest }.mapNotNull { dto ->
                        decryptIncomingMessage(dto.senderId, Message(dto.id, dto.chatId, dto.senderId, dto.content, MessageType.fromWire(dto.type), dto.timestamp, MessageStatus.fromWire(dto.status)))
                    }
                }
                if (newMessages.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(messages = mergeMessageLists(state.messages, newMessages))
                    }
                    withContext(Dispatchers.IO) { messageRepo.insertMessages(newMessages) }
                }
            }
        }
    }'''

s = s.replace(old_ws, new_ws)

# Fix observeMessageStatus type mismatch — 用内容 hash 而非引用相等
s = s.replace(
    'if (state.chat != null && state.messages.map { it.id to it.status } != lastMessagesSeen) {\n                    lastMessagesSeen = state.messages',
    'if (state.chat != null) {\n                    val currentIds = state.messages.map { it.id to it.status }\n                    if (currentIds != lastMessagesSeen) {\n                        lastMessagesSeen = currentIds')

# close the extra brace
s = s.replace(
    '                    }\n                }\n            }\n        }\n    }\n\n    // 实例级 scope',
    '                    }\n                }\n            }\n        }\n    }\n')

# resolveDirectServerChatId 幂等 — 先查已有 activeChatId
s = s.replace(
    '''    private suspend fun resolveDirectServerChatId(recipientId: String): Result<String> {
        if (_uiState.value.chat?.isGroup == true) return Result.success(activeChatId)
        if (token.isBlank()) return Result.failure(IllegalStateException("未登录"))
        if (recipientId.isBlank()) return Result.failure(IllegalStateException("聊天对象未就绪"))
        if (recipientId == currentUserId) return Result.failure(IllegalStateException("不能向自己发送"))''',
    '''    private suspend fun resolveDirectServerChatId(recipientId: String): Result<String> {
        if (_uiState.value.chat?.isGroup == true) return Result.success(activeChatId)
        if (token.isBlank()) return Result.failure(IllegalStateException("未登录"))
        if (recipientId.isBlank()) return Result.failure(IllegalStateException("聊天对象未就绪"))
        if (recipientId == currentUserId) return Result.failure(IllegalStateException("不能向自己发送"))
        // 幂等：已有有效 activeChatId 则直接复用
        if (activeChatId.isNotBlank() && activeChatId != "c1") return Result.success(activeChatId)''')

p.write_text(s)
print('ChatDetailViewModel fixed')
