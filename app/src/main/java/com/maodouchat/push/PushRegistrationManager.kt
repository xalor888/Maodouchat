package com.maodouchat.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.maodouchat.BuildConfig
import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.security.BackgroundSessionGate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

/** Manual, optional Firebase setup that does not require google-services.json. */
object PushRegistrationManager {
    private const val TAG = "PushRegistration"
    private const val PREFS_NAME = "push_installation"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_REGISTERED_AT = "last_registered_at"
    private const val KEY_REGISTRATION_STATE = "registration_state"

    /** 推送注册状态：供通知设置页展示详细通道健康度 */
    enum class RegistrationState {
        UNKNOWN, INITIALIZING, REGISTERED, FAILED
    }

    @Volatile
    private var configured = false
    @Volatile
    private var registrationState: RegistrationState = RegistrationState.UNKNOWN
    @Volatile
    private var lastRegistrationError: String? = null

    /** BuildConfig has FCM project fields (not necessarily live-registered). */
    fun hasFirebaseConfiguration(): Boolean {
        return BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_SENDER_ID.isNotBlank()
    }

    /** FirebaseApp initialized successfully this process. */
    fun isConfigured(): Boolean = configured

    /** 当前推送注册状态（供 UI 展示）。 */
    fun getRegistrationState(): RegistrationState = registrationState

    /** Logout/account switch only; Firebase remains initialized for the next login. */
    @Synchronized
    fun resetRegistrationStateForAccountChange(context: Context) {
        registrationState = RegistrationState.UNKNOWN
        lastRegistrationError = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REGISTRATION_STATE, RegistrationState.UNKNOWN.name)
            .apply()
    }

    /** 最近一次注册失败原因（供 UI 展示）。 */
    fun getLastRegistrationError(): String? = lastRegistrationError

    /** 最近一次成功注册的时间戳（ms）；0 = 从未注册。从 SharedPreferences 持久化读取。 */
    fun getLastRegisteredAt(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REGISTERED_AT, 0L)
    }

    @Synchronized
    private fun persistRegistrationStateIfCurrent(
        context: Context,
        tokenManager: TokenManager,
        expectedOwnerUserId: String,
        state: RegistrationState,
        error: String? = null,
    ): Boolean {
        if (!isCurrentOwner(tokenManager, expectedOwnerUserId)) return false
        registrationState = state
        lastRegistrationError = error
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REGISTRATION_STATE, state.name)
            .apply()
        return true
    }

    @Synchronized
    private fun markRegisteredIfCurrent(
        context: Context,
        tokenManager: TokenManager,
        expectedOwnerUserId: String,
    ): Boolean {
        if (!isCurrentOwner(tokenManager, expectedOwnerUserId)) return false
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REGISTERED_AT, now)
            .putString(KEY_REGISTRATION_STATE, RegistrationState.REGISTERED.name)
            .apply()
        registrationState = RegistrationState.REGISTERED
        lastRegistrationError = null
        return true
    }

    fun initialize(context: Context): Boolean {
        if (!hasFirebaseConfiguration()) return false
        return runCatching {
            if (FirebaseApp.getApps(context).none { it.name == "[DEFAULT]" }) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            configured = true
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            refreshRegistration(context)
            true
        }.onFailure { Log.w(TAG, "Firebase initialization failed; push remains disabled", it) }
            .getOrDefault(false)
    }

    fun refreshRegistration(context: Context) {
        if (!configured) return
        val tokenManager = TokenManager.getInstance(context)
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return
        if (!isCurrentOwner(tokenManager, ownerUserId)) return
        if (!persistRegistrationStateIfCurrent(
                context = context,
                tokenManager = tokenManager,
                expectedOwnerUserId = ownerUserId,
                state = RegistrationState.INITIALIZING,
            )
        ) {
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                registerToken(context, token, ownerUserId)
            }
            .addOnFailureListener { error ->
                if (!isCurrentOwner(tokenManager, ownerUserId)) return@addOnFailureListener
                Log.w(TAG, "Unable to obtain FCM token", error)
                persistRegistrationStateIfCurrent(
                    context = context,
                    tokenManager = tokenManager,
                    expectedOwnerUserId = ownerUserId,
                    state = RegistrationState.FAILED,
                    error = error.message,
                )
            }
    }

    fun registerToken(context: Context, pushToken: String) {
        val tokenManager = TokenManager.getInstance(context)
        val ownerUserId = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return
        registerToken(context, pushToken, ownerUserId)
    }

    private fun registerToken(context: Context, pushToken: String, expectedOwnerUserId: String) {
        if (!configured || pushToken.isBlank() || expectedOwnerUserId.isBlank()) return
        val app = context.applicationContext as? MaodouchatApp ?: return
        val tokenManager = TokenManager.getInstance(context)
        if (!isCurrentOwner(tokenManager, expectedOwnerUserId)) return
        app.applicationScope.launch {
            // Logout/account switch between schedule and REST: do not bind FCM to the next JWT.
            if (!isCurrentOwner(tokenManager, expectedOwnerUserId)) return@launch
            val liveAccess = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return@launch
            ApiService.registerPushToken(
                token = liveAccess,
                deviceId = installationId(context),
                pushToken = pushToken,
                timezoneOffsetMinutes = timezoneOffsetMinutes()
            ).onSuccess {
                if (!markRegisteredIfCurrent(context, tokenManager, expectedOwnerUserId)) return@onSuccess
                Log.i(TAG, "Push token registered successfully")
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!isCurrentOwner(tokenManager, expectedOwnerUserId)) return@onFailure
                Log.w(TAG, "Push token registration failed", error)
                persistRegistrationStateIfCurrent(
                    context = context,
                    tokenManager = tokenManager,
                    expectedOwnerUserId = expectedOwnerUserId,
                    state = RegistrationState.FAILED,
                    error = error.message,
                )
            }
        }
    }

    private fun isCurrentOwner(tokenManager: TokenManager, expectedOwnerUserId: String): Boolean {
        return BackgroundSessionGate.mayContinue(
            expectedUserId = expectedOwnerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )
    }

    suspend fun unregisterCurrentDevice(context: Context, accessToken: String) {
        if (!configured || accessToken.isBlank()) return
        ApiService.removePushToken(accessToken, installationId(context)).getOrThrow()
    }

    /** 当前安装的推送 deviceId（与 register/unregister 一致）；logout 兜底清理用。 */
    fun currentDeviceId(context: Context): String = installationId(context)

    // @Synchronized：两个并发首次调用会各自生成 UUID，一个持久化、另一个把不同的 ID
    // 注册到服务器，导致 logout 兜底清理删错 deviceId。
    @Synchronized
    private fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotBlank)?.let { return it }
        val generated = UUID.randomUUID().toString()
        // 8.40：首次生成必须 commit() 同步落盘——apply() 是异步的，生成后立即被杀会换新
        // deviceId 并注册，服务端保留旧 id 的 FCM 绑定（旧 token 持续推送但不再被 unregister）
        @Suppress("ApplySharedPref")
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    private fun timezoneOffsetMinutes(): Int {
        return ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60
    }

    }
