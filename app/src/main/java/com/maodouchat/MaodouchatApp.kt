package com.maodouchat

import android.app.Application
import android.content.Context
import android.os.Build
import com.maodouchat.crypto.SenderKeyRetryManager
import com.maodouchat.crypto.SenderKeyRetryWorkScheduler
import com.maodouchat.ai.AiTaskReminderScheduler
import com.maodouchat.attachment.AttachmentTransferCoordinator
import com.maodouchat.attachment.AttachmentTransferScheduler
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.network.ApiService
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import com.maodouchat.push.PushRegistrationManager
import com.maodouchat.util.AppLocaleManager
import com.maodouchat.security.SecureSessionManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Tap from FCM/system call notification → wake IncomingCallObserver to poll
 * pending offers (SDP lives server-side; FCM only carries callId/sender).
 */
data class IncomingCallWake(
    val callId: String = "",
    val senderId: String = "",
    val isVideo: Boolean = false,
    val atMillis: Long = System.currentTimeMillis(),
    /** Matches [MaodouchatApp.currentSessionGeneration] at emit time; collectors drop stale wakes. */
    val sessionGeneration: Long = 0L,
    val requestId: Long = 0L,
    /** 8.56：系统 Telecom 来电 UI 点击「接听」后为 true——IncomingCallRoute 直接自动接听。 */
    val autoAnswer: Boolean = false,
)

/**
 * Application 类 — 全局初始化
 *
 * 启动优化：加密数据库（SQLCipher ~100ms+ 解密）+ SignalProtocol 初始化移到后台线程，
 * 不阻塞首帧渲染。database / signalProtocol / secureSessionManager 均延迟到首次访问时创建，
 * 且重建逻辑（rebuildLocalStorage）仍然有效。
 */
class MaodouchatApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(base))
        // 8.48：冷启动计时（B7 启动预算：首帧 <1.2s / 可交互 <1.8s），配合 systrace/perfetto
        com.maodouchat.perf.StartupTracer.beginColdStart()
    }

    @Volatile private var _database: AppDatabase? = null
    @Volatile private var _signalProtocol: SignalProtocol? = null
    @Volatile private var _secureSessionManager: SecureSessionManager? = null
    @Volatile private var _senderKeyRetryManager: SenderKeyRetryManager? = null
    @Volatile private var _imageOcrAutoIndexer: com.maodouchat.ai.ImageOcrAutoIndexer? = null

    // 延迟初始化 + 双检锁：后台线程首次访问时创建，不阻塞 onCreate
    val database: AppDatabase
        get() = _database ?: synchronized(this) {
            _database ?: AppDatabase.getInstance(this).also { _database = it }
        }

    val signalProtocol: SignalProtocol
        get() = _signalProtocol ?: synchronized(this) {
            _signalProtocol ?: SignalProtocol(database.signalKeyDao(), database.identityTrustDao())
                .also { _signalProtocol = it }
        }

    val secureSessionManager: SecureSessionManager
        get() = _secureSessionManager ?: synchronized(this) {
            _secureSessionManager ?: SecureSessionManager(
                context = this,
                database = database,
                signalProtocol = signalProtocol,
                tokenManager = TokenManager.getInstance(this),
                onEncryptedDatabaseDestroyed = { rebuildLocalStorage() }
            ).also { _secureSessionManager = it }
        }

    val senderKeyRetryManager: SenderKeyRetryManager
        get() = _senderKeyRetryManager ?: synchronized(this) {
            _senderKeyRetryManager ?: SenderKeyRetryManager(
                retryDao = database.senderKeyRetryDao(),
                signalProtocol = signalProtocol,
                tokenManager = TokenManager.getInstance(this),
                context = this
            ).also { _senderKeyRetryManager = it }
        }

    /** 自动图片 OCR：识别图内文字并写入搜索索引（随应用启动与开关触发）。 */
    val imageOcrAutoIndexer: com.maodouchat.ai.ImageOcrAutoIndexer
        get() = _imageOcrAutoIndexer ?: synchronized(this) {
            _imageOcrAutoIndexer ?: com.maodouchat.ai.ImageOcrAutoIndexer(
                context = this,
                database = database
            ).also { _imageOcrAutoIndexer = it }
        }

    /** 通知中心聚合仓库：统一持久化所有的应用层通知事件 */
    val notificationCenter: NotificationCenterRepository by lazy { NotificationCenterRepository(this) }

    // 全局应用作用域 — 用 SupervisorJob + CoroutineExceptionHandler 避免未捕获异常直接崩溃
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("MaodouchatApp", "applicationScope coroutine failed", throwable)
        }
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 进程在通话中被系统杀死后，AudioManager 的 MODE_IN_COMMUNICATION 不会自动复位，
        // 会污染后续所有通话/系统音频。启动早期主动复位（此时本进程无活动通话）。
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            if (am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = android.media.AudioManager.MODE_NORMAL
            }
        } catch (_: Throwable) { /* 启动期音频复位失败不应阻断应用启动 */ }
        // 8.45：启动即加载运行时服务器配置（设置页可配置，免重新构建 APK），
        // 必须在任何网络调用（含 ImageLoader 的 apiHost 解析）之前完成
        ApiConfig.init(this)
        ApiService.configure(TokenManager.getInstance(this))
        PushRegistrationManager.initialize(this)
        // 8.48：Application 初始化完成里程碑（DB/Signal/依赖就绪）
        com.maodouchat.perf.StartupTracer.mark("applicationInit")
        rebuildImageLoader()

        // 通知渠道必须在主线程建（系统要求）
        com.maodouchat.util.AppNotifier.ensureChannels(this)

        // ConnectionService：注册系统通话 PhoneAccount（用于来电时接管原生通话 UI）
        com.maodouchat.telecom.TelecomHelper.registerPhoneAccount(this)

        // 1.103：会话列表「正在输入」presence——订阅全局 WS 事件流（进程级单例，随连接生灭自清理）
        com.maodouchat.util.TypingPresenceStore.start(com.maodouchat.network.WebSocketClient.events)

        // 把加密数据库 + SignalProtocol 初始化移到后台线程，避免阻塞首帧
        applicationScope.launch {
            // 触发 lazy 创建（SQLCipher 解密 ~100ms）
            database
            signalProtocol
            // Cold start while still logged in: restore identity/sessions before chat crypto
            val tokenManager = TokenManager.getInstance(this@MaodouchatApp)
            val userId = tokenManager.getUserId()
            val token = tokenManager.getToken()
            if (!userId.isNullOrBlank() && tokenManager.isLoggedIn()) {
                try {
                    // 8.42：挂起点前复核会话未切换——冷启动期间极速登出/换号时，
                    // 捕获的旧账号 userId/token 不得向新账号的数据库写入密钥状态
                    fun stillCurrent(): Boolean =
                        com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = userId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    if (!stillCurrent()) return@launch
                    signalProtocol.initialize(token, userId)
                    if (!stillCurrent()) return@launch
                    // 运行时补充 PreKey：冷启动后检查池是否低于阈值，低于则生成+上传
                    signalProtocol.replenishPreKeysIfNeeded(token)
                    if (!stillCurrent()) return@launch
                    // Signed PreKey 轮换：超过 7 天则自动轮换
                    signalProtocol.rotateSignedPreKeyIfNeeded(token)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("MaodouchatApp", "Signal restore on cold start failed", error)
                }
            }
            senderKeyRetryManager.start(applicationScope)
            AiTaskReminderScheduler.ensureScheduled(this@MaodouchatApp)
            AttachmentTransferCoordinator.reconcile(this@MaodouchatApp)
            // 自动图片 OCR：识别图内文字入搜索索引（静默，条件不满足即整体跳过）
            if (!userId.isNullOrBlank() && tokenManager.isLoggedIn()) {
                try {
                    imageOcrAutoIndexer.runOnce()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("MaodouchatApp", "auto image OCR scan failed", error)
                }
            }
            // Process death while SENDING text: flush without waiting for ChatList/ChatDetail.
            if (!userId.isNullOrBlank() && tokenManager.isLoggedIn()) {
                try {
                    com.maodouchat.data.repository.TextOutboxFlusher.flush(app = this@MaodouchatApp)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("MaodouchatApp", "cold-start text outbox flush failed", error)
                }
                try {
                    com.maodouchat.util.ClientPrefsSync.pullAndApply(this@MaodouchatApp)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("MaodouchatApp", "cold-start client prefs pull failed", error)
                }
            }
        }

        // 运行期周期性补充 PreKey / 轮换 SignedPreKey：冷启动之外，长会话也需维持前向安全
        applicationScope.launch {
            while (true) {
                delay(6L * 60 * 60 * 1000) // 每 6 小时检查一次
                val tm = TokenManager.getInstanceOrNull() ?: continue
                if (!tm.isLoggedIn()) continue
                val t = tm.getToken()?.takeIf(String::isNotBlank) ?: continue
                try { signalProtocol.replenishPreKeysIfNeeded(t) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("MaodouchatApp", "PreKey replenish failed", e) }
                try { signalProtocol.rotateSignedPreKeyIfNeeded(t) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("MaodouchatApp", "SignedPreKey rotation failed", e) }
            }
        }

        // 阅后即焚：即使没有聊天界面打开，也周期性清除过期消息（不依赖 ChatDetailViewModel 的 1 秒循环），
        // 避免聊天关闭/进程存活但无界面时私密内容永久留存于 Room。
        applicationScope.launch {
            while (isActive) {
                delay(60_000L * 5) // 每 5 分钟扫描一次过期消息
                val tm = TokenManager.getInstanceOrNull() ?: continue
                if (!tm.isLoggedIn()) continue
                try {
                    val now = System.currentTimeMillis()
                    val ids = database.messageDao().getExpiredMessageIds(now)
                    if (ids.isNotEmpty()) {
                        // 删除前取所属 chatId（删除后消息行不存在，无法反查）
                        val expiredChatIds = ids.mapNotNull { firstId ->
                            database.messageDao().getMessageById(firstId)?.chatId
                        }.distinct()
                        // 8.47：分批删除（SQLite 变量数上限 ~999），避免大批自毁消息同时到期崩溃
                        ids.chunked(900).forEach { chunk ->
                            database.messageDao().deleteMessagesByIds(chunk)
                        }
                        ids.forEach { com.maodouchat.util.MediaCache.deleteCachedMediaForMessage(this@MaodouchatApp, it) }
                        // 8.32 修复 F9（隐私）：自毁消息清除后同步清理通知中心条目与 tray 预览
                        runCatching {
                            com.maodouchat.data.repository.NotificationCenterRepository(this@MaodouchatApp)
                                .deleteItemsForMessages(ids)
                        }
                        runCatching {
                            expiredChatIds.forEach { chatIdOfExpired ->
                                com.maodouchat.util.AppNotifier.cancelMessage(this@MaodouchatApp, chatIdOfExpired)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("MaodouchatApp", "Expired message cleanup failed", e)
                }
            }
        }

        // 媒体/附件缓存维护：MediaCache.cleanup 此前无调用方，只会在登出/重建时全清；
        // 这里每 6 小时按年龄与总字节上限清理一次，避免长期运行只涨不清。
        applicationScope.launch {
            while (isActive) {
                delay(6L * 60 * 60 * 1000)
                try {
                    withContext(Dispatchers.IO) {
                        com.maodouchat.util.MediaCache.cleanup(this@MaodouchatApp)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("MaodouchatApp", "Media cache cleanup failed", e)
                }
            }
        }

    }

    /**
     * 重建本地存储：SQLCipher 密码变更或数据库损坏时调用。
     * 关闭旧库 → 清空引用 → 下次访问时 lazy 重建。
     */

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private fun clearCoilImageCaches() {
        val loader = coil.Coil.imageLoader(this)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    @Synchronized
    /**
     * 8.48 修复：切换服务器后重建 Coil ImageLoader——apiHost/DNS/授权头拦截器
     * 按启动时的旧服务器构建，切换后 `/api/files/` 图片不再带 token（401）、
     * 且 PublicNetworkDns 对新主机（局域网 IP）硬阻断。setServer 成功后调用。
     */
    fun rebuildImageLoader() {
        val maxMemoryBytes = Runtime.getRuntime().maxMemory()
        val memoryCachePct = if (maxMemoryBytes <= com.maodouchat.perf.ImageMemoryPolicy.LOW_MEMORY_HEAP_THRESHOLD_BYTES) {
            com.maodouchat.perf.ImageMemoryPolicy.MEMORY_CACHE_PERCENT_LOW
        } else {
            com.maodouchat.perf.ImageMemoryPolicy.MEMORY_CACHE_PERCENT_NORMAL
        }
        val imageLoader = coil.ImageLoader.Builder(this)
            .okHttpClient {
                val apiHost = ApiConfig.BASE_URL.toHttpUrlOrNull()?.host
                OkHttpClient.Builder()
                    .dns(com.maodouchat.util.PublicNetworkDns.create(setOfNotNull(apiHost)))
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val isProtectedAppImage = apiHost != null && request.url.host == apiHost &&
                            (request.url.encodedPath.startsWith("/api/files/") ||
                                request.url.encodedPath.contains("/avatar/file/"))
                        val token = if (isProtectedAppImage) TokenManager.getInstance(this).getToken() else null
                        chain.proceed(
                            if (token.isNullOrBlank()) request
                            else request.newBuilder().header("Authorization", "Bearer $token").build()
                        )
                    }.build()
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(memoryCachePct)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(com.maodouchat.perf.ImageMemoryPolicy.DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(coil.decode.ImageDecoderDecoder.Factory())
                else add(coil.decode.GifDecoder.Factory())
            }
            .build()
        coil.Coil.setImageLoader(imageLoader)
    }

    @Synchronized
    fun rebuildLocalStorage() {
        runCatching { _senderKeyRetryManager?.stop() }
        runCatching { SenderKeyRetryWorkScheduler.cancelAll(this) }
        runCatching { AiTaskReminderScheduler.cancelAll(this) }
        runCatching { AttachmentTransferScheduler.cancelAll(this) }
        runCatching { com.maodouchat.util.MediaCache.cleanupReturningBytes(this) }
        runCatching { clearCoilImageCaches() }
        // 8.47 修复：OCR indexer 持有 AppDatabase 引用——密文库销毁重建后必须一并置空，
        // 否则下次 runOnce()（启动/设置页开关）用已关闭的库抛 SQLiteClosedException
        runCatching { AppDatabase.closeInstance() }
        _database = null
        _signalProtocol = null
        _secureSessionManager = null
        _senderKeyRetryManager = null
        _imageOcrAutoIndexer = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 低内存：清 Coil 图片内存缓存（最大内存占用之一），避免 OOM 崩溃
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_RUNNING_LOW 及以上：清图片内存缓存
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    companion object {
        lateinit var instance: MaodouchatApp
            private set

        /**
         * 跨 ViewModel 共享的"已读事件"流：
         * ChatDetailViewModel 标记消息已读后 emit chatId，
         * ChatListViewModel 收集后将对应聊天的 unreadCount 归零。
         */
        data class ChatReadEvent(
            val chatId: String,
            val sessionGeneration: Long = currentSessionGeneration(),
        )
        private val _chatReadEvents = kotlinx.coroutines.flow.MutableSharedFlow<ChatReadEvent>(extraBufferCapacity = 16)
        val chatReadEvents = _chatReadEvents.asSharedFlow()
        fun emitChatRead(chatId: String) {
            _chatReadEvents.tryEmit(ChatReadEvent(chatId = chatId, sessionGeneration = sessionGeneration))
        }

        /**
         * 跨 ViewModel 共享的"列表预览变更"流：
         * - 发送/附件 finalize：带 previewText + typeWire，列表单调更新时间
         * - delete/revoke 本地成功：forceFromLocal=true，列表从 Room 重算 tail（可清空/回退）
         */
        data class ChatMessageSentEvent(
            val chatId: String,
            val previewText: String = "",
            val messageTypeWire: String = "TEXT",
            val forceFromLocal: Boolean = false,
            val sessionGeneration: Long = currentSessionGeneration(),
        )
        private val _chatMessageSentEvents =
            kotlinx.coroutines.flow.MutableSharedFlow<ChatMessageSentEvent>(extraBufferCapacity = 16)
        val chatMessageSentEvents = _chatMessageSentEvents.asSharedFlow()
        fun emitMessageSent(
            chatId: String,
            previewText: String,
            messageTypeWire: String = "TEXT"
        ) {
            // 0.81：应用内发送成功提示音（受 IN_APP_SOUNDS flag 门控）
            com.maodouchat.util.InAppSoundPlayer.playSendTone()
            _chatMessageSentEvents.tryEmit(
                ChatMessageSentEvent(
                    chatId = chatId,
                    previewText = previewText,
                    messageTypeWire = messageTypeWire,
                    forceFromLocal = false,
                    sessionGeneration = sessionGeneration,
                )
            )
        }
        /** After local delete/revoke (or head edit), list recomputes lastMessage from Room. */
        fun emitChatListPreviewRefresh(chatId: String) {
            if (chatId.isBlank()) return
            _chatMessageSentEvents.tryEmit(
                ChatMessageSentEvent(
                    chatId = chatId,
                    forceFromLocal = true,
                    sessionGeneration = sessionGeneration,
                )
            )
        }

        data class AttachmentFinalizedEvent(
            val message: com.maodouchat.data.model.Message,
            val sessionGeneration: Long = currentSessionGeneration(),
        )
        private val _attachmentFinalizedEvents =
            kotlinx.coroutines.flow.MutableSharedFlow<AttachmentFinalizedEvent>(extraBufferCapacity = 16)
        val attachmentFinalizedEvents = _attachmentFinalizedEvents.asSharedFlow()
        fun emitAttachmentFinalized(message: com.maodouchat.data.model.Message) {
            _attachmentFinalizedEvents.tryEmit(
                AttachmentFinalizedEvent(message = message, sessionGeneration = sessionGeneration)
            )
        }

        /**
         * 当前正在查看的聊天 ID（用于跨 ViewModel 同步）。
         * ChatDetailViewModel 初始化时设置，onCleared 时清除。
         * ChatListViewModel 据此跳过活跃聊天的未读数递增，避免竞态。
         */
        @Volatile
        var activeChatId: String? = null

        /**
         * Process-local session epoch for deep-links / buffered navigation.
         * Bumped on logout so pending notification targets and open-missed events
         * from the previous account cannot fire after the next login.
         */
        @Volatile
        private var sessionGeneration: Long = 0L
        private val navigationRequestSequence = java.util.concurrent.atomic.AtomicLong(0L)

        fun currentSessionGeneration(): Long = sessionGeneration

        fun invalidateSessionGeneration() {
            sessionGeneration += 1L
        }

        /** 推送/通知中心统一入口：保存"通知中心聚合"条目。 */
        fun emitNotificationCenterItem(item: NotificationCenterItem, expectedUserId: String? = null) {
            instance.notificationCenter.add(item, expectedUserId)
        }

        private val _incomingCallWakeEvents =
            kotlinx.coroutines.flow.MutableStateFlow<IncomingCallWake?>(null)
        val incomingCallWakeEvents = _incomingCallWakeEvents.filterNotNull()
        fun emitIncomingCallWake(wake: IncomingCallWake = IncomingCallWake()) {
            _incomingCallWakeEvents.value = wake.copy(
                sessionGeneration = wake.sessionGeneration.takeIf { it != 0L } ?: sessionGeneration,
                requestId = navigationRequestSequence.incrementAndGet(),
            )
        }
        fun consumeIncomingCallWake(wake: IncomingCallWake) {
            _incomingCallWakeEvents.compareAndSet(wake, null)
        }

        /**
         * Tap on a missed-call system notification → main chats tab + inbox so the
         * missed-call card is visible (EXTRA_OPEN_MISSED_CALL was previously unused).
         */
        data class OpenMissedCallsRequest(
            val atMillis: Long,
            val sessionGeneration: Long,
            val requestId: Long,
        )
        private val _openMissedCallsEvents =
            kotlinx.coroutines.flow.MutableStateFlow<OpenMissedCallsRequest?>(null)
        val openMissedCallsEvents = _openMissedCallsEvents.filterNotNull()
        fun emitOpenMissedCalls(atMillis: Long = System.currentTimeMillis()) {
            _openMissedCallsEvents.value = OpenMissedCallsRequest(
                atMillis = atMillis,
                sessionGeneration = sessionGeneration,
                requestId = navigationRequestSequence.incrementAndGet(),
            )
        }
        fun consumeOpenMissedCalls(request: OpenMissedCallsRequest) {
            _openMissedCallsEvents.compareAndSet(request, null)
        }

        /**
         * Notification-center / tray deep-link → main contacts tab
         * (friend requests, contact-related center rows).
         */
        data class OpenContactsRequest(
            val atMillis: Long,
            val sessionGeneration: Long,
            val requestId: Long,
        )
        private val _openContactsEvents =
            kotlinx.coroutines.flow.MutableStateFlow<OpenContactsRequest?>(null)
        val openContactsEvents = _openContactsEvents.filterNotNull()
        fun emitOpenContacts(atMillis: Long = System.currentTimeMillis()) {
            _openContactsEvents.value = OpenContactsRequest(
                atMillis = atMillis,
                sessionGeneration = sessionGeneration,
                requestId = navigationRequestSequence.incrementAndGet(),
            )
        }
        fun consumeOpenContacts(request: OpenContactsRequest) {
            _openContactsEvents.compareAndSet(request, null)
        }
    }
}
