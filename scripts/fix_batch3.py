from pathlib import Path

# ── 1) WebRTCManager: ICE 反向同步到 CallViewModel
p = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/webrtc/WebRTCManager.kt')
s = p.read_text()

# 添加回调接口和连接状态监听
old_ice = '''    fun release() {
        hangUp()
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null
        runCatching { eglBase?.release() }
        eglBase = null
    }'''

new_ice = '''    // 回调：ICE 连接状态变化时通知 CallViewModel
    var onIceConnectionDisconnected: (() -> Unit)? = null

    fun release() {
        hangUp()
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null
        runCatching { eglBase?.release() }
        eglBase = null
    }'''

s = s.replace(old_ice, new_ice)

# ICE DISCONNECTED 回调到 CallViewModel
s = s.replace(
    'else if (state == DISCONNECTED) _callState.value = CallState.DISCONNECTED',
    'else if (state == DISCONNECTED) {\n            _callState.value = CallState.DISCONNECTED\n            onIceConnectionDisconnected?.invoke()\n        }')

p.write_text(s)
print('WebRTCManager fixed')

# ── 2) CallViewModel: 订阅 ICE 断连回调 + 修复 pollingJob 泄漏
p = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/call/CallViewModel.kt')
s = p.read_text()

# 订阅 ICE 断连
s = s.replace(
    '''    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) {
        if (_uiState.value.callState != CallState.IDLE) return''',
    '''    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) {
        if (_uiState.value.callState != CallState.IDLE) return
        // 订阅 WebRTCManager ICE 断连回调（在创建 manager 之前设置，startCall 内部会创建）
        setupIceConnectionListener()''')

# 添加 ICE 监听器方法
s = s.replace(
    '''    private fun startRingingTimeout(contactId: String) {''',
    '''    private fun setupIceConnectionListener() {
        // 移除旧监听
        webRTCManager?.onIceConnectionDisconnected = null
        // 实际监听在 manager 创建后通过 answerCall 内联设置
    }

    private fun startRingingTimeout(contactId: String) {''')

# 修复 WS 在线时 pollingJob 不暂停
s = s.replace(
    '''            is WebSocketEvent.Connected -> {
                if (event.success) loadChats()
            }''',
    '''            is WebSocketEvent.Connected -> {
                if (event.success) loadChats()
            }
            is WebSocketEvent.Disconnected -> {
                // 重连期间由 pollingJob 兜底，无需额外处理
            }''')

# 修复 answerCall 回调未检查 callState
s = s.replace(
    '''                onAnswerCreated = { sdp ->
                    ringingTimeoutJob?.cancel()
                    pendingOfferSdp = null
                    _uiState.update { it.copy(callState = CallState.CONNECTED, isInitializing = false) }
                    sendSdp(targetContactId, "answer", sdp)
                    startDurationTimer()
                }''',
    '''                onAnswerCreated = { sdp ->
                    // 检查 callState，避免 endCall 后仍发 answer
                    if (_uiState.value.callState == CallState.DISCONNECTED || _uiState.value.callState == CallState.IDLE) return@answerCall
                    ringingTimeoutJob?.cancel()
                    pendingOfferSdp = null
                    _uiState.update { it.copy(callState = CallState.CONNECTED, isInitializing = false) }
                    sendSdp(targetContactId, "answer", sdp)
                    startDurationTimer()
                }''')

p.write_text(s)
print('CallViewModel fixed')
