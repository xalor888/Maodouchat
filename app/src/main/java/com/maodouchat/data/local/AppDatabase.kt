package com.maodouchat.data.local

import android.content.Context
import com.maodouchat.BuildConfig
import androidx.room.Database
import androidx.room.RoomDatabase
import com.maodouchat.data.local.dao.AiSummaryCacheDao
import com.maodouchat.data.local.dao.AiTaskDao
import com.maodouchat.data.local.dao.AiOperationDao
import com.maodouchat.data.local.dao.AttachmentTransferDao
import com.maodouchat.data.local.dao.ChatDao
import com.maodouchat.data.local.dao.ChatDraftDao
import com.maodouchat.data.local.dao.ChatLockDao
import com.maodouchat.data.local.dao.IdentityTrustDao
import com.maodouchat.data.local.dao.MessageDao
import com.maodouchat.data.local.dao.MessageSearchDao
import com.maodouchat.data.local.dao.MissedCallDao
import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.dao.NotificationCenterDao
import com.maodouchat.data.local.dao.SecretChatDao
import com.maodouchat.data.local.dao.MessageReminderDao
import com.maodouchat.data.local.dao.ArchiveDismissalDao
import com.maodouchat.data.local.dao.ScheduledMessageDao
import com.maodouchat.data.local.dao.SenderKeyRetryDao
import com.maodouchat.data.local.dao.SignalKeyDao
import com.maodouchat.data.local.dao.UserDao
import com.maodouchat.data.local.dao.VoicePlayedDao
import com.maodouchat.data.local.entity.AiSummaryCacheEntity
import com.maodouchat.data.local.entity.ArchiveSuggestionDismissalEntity
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.data.local.entity.AiOperationEntity
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.local.entity.ChatLockEntity
import com.maodouchat.data.local.entity.IdentityTrustEntity
import com.maodouchat.data.local.entity.MessageEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessageReminderEntity
import com.maodouchat.data.local.entity.MessageSearchDocumentEntity
import com.maodouchat.data.local.entity.MessageSearchTokenEntity
import com.maodouchat.data.local.entity.MissedCallEntity
import com.maodouchat.data.local.entity.NotificationCenterItemEntity
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import com.maodouchat.data.local.entity.ScheduledMessageEntity
import com.maodouchat.data.local.entity.SecretChatEntity
import com.maodouchat.data.local.entity.SenderKeyRetryEntity
import com.maodouchat.data.local.entity.SignalKeyEntity
import com.maodouchat.data.local.entity.UserEntity
import com.maodouchat.data.local.entity.VoicePlayedEntity

@Database(
    entities = [
        UserEntity::class,
        ChatEntity::class,
        ChatDraftEntity::class,
        MessageEntity::class,
        SignalKeyEntity::class,
        IdentityTrustEntity::class,
        MissedCallEntity::class,
        ChatLockEntity::class,
        SecretChatEntity::class,
        AiSummaryCacheEntity::class,
        SenderKeyRetryEntity::class,
        AiTaskEntity::class,
        MessageSearchDocumentEntity::class,
        MessageSearchTokenEntity::class,
        AttachmentTransferEntity::class,
        AiOperationEntity::class,
        MessagingV2InboxEntity::class,
        MessagingV2OutboxEntity::class,
        MessagingV2ReceiptEntity::class,
        MessageMutationTombstoneEntity::class,
        ScheduledMessageEntity::class,
        MessageReminderEntity::class,
        ArchiveSuggestionDismissalEntity::class,
        VoicePlayedEntity::class,
        NotificationCenterItemEntity::class,
    ],
    version = 40,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun chatDraftDao(): ChatDraftDao
    abstract fun messageDao(): MessageDao
    abstract fun messageSearchDao(): MessageSearchDao
    abstract fun signalKeyDao(): SignalKeyDao
    abstract fun identityTrustDao(): IdentityTrustDao
    abstract fun missedCallDao(): MissedCallDao
    abstract fun chatLockDao(): ChatLockDao
    abstract fun secretChatDao(): SecretChatDao
    abstract fun aiSummaryCacheDao(): AiSummaryCacheDao
    abstract fun aiTaskDao(): AiTaskDao
    abstract fun aiOperationDao(): AiOperationDao
    abstract fun senderKeyRetryDao(): SenderKeyRetryDao
    abstract fun attachmentTransferDao(): AttachmentTransferDao
    abstract fun messagingV2Dao(): MessagingV2Dao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun messageReminderDao(): MessageReminderDao
    abstract fun archiveDismissalDao(): ArchiveDismissalDao
    abstract fun voicePlayedDao(): VoicePlayedDao
    abstract fun notificationCenterDao(): NotificationCenterDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseLifecycle.open(context.applicationContext, allowRecreate = BuildConfig.DEBUG)
                    .also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.let { DatabaseLifecycle.close(it) }
                INSTANCE = null
            }
        }

        fun destroyDatabase(context: Context) {
            synchronized(this) {
                INSTANCE?.let { DatabaseLifecycle.close(it) }
                INSTANCE = null
                DatabaseLifecycle.destroy(context.applicationContext)
            }
        }
    }
}
