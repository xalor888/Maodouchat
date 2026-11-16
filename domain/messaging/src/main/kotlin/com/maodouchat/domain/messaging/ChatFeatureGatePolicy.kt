package com.maodouchat.domain.messaging

// 聊天详情功能门禁：判定本体是纯布尔组合；开关取值（RuntimeFlags）与警告写入仍在 app 侧薄壳。
object ChatFeatureGatePolicy {

    sealed interface VoiceMessagesGate {
        data object Allowed : VoiceMessagesGate
        data object VoiceDisabled : VoiceMessagesGate
        data object MediaUploadDisabled : VoiceMessagesGate
    }

    fun voiceMessagesGate(voiceEnabled: Boolean, mediaUploadEnabled: Boolean): VoiceMessagesGate =
        when {
            !voiceEnabled -> VoiceMessagesGate.VoiceDisabled
            !mediaUploadEnabled -> VoiceMessagesGate.MediaUploadDisabled
            else -> VoiceMessagesGate.Allowed
        }

    fun stickersAllowed(stickersEnabled: Boolean): Boolean = stickersEnabled

    fun silentSendAllowed(silentSendEnabled: Boolean): Boolean = silentSendEnabled

    sealed interface ReactionsGate {
        data object Allowed : ReactionsGate
        data object ReactionsDisabled : ReactionsGate
        data object SecretReactionBlocked : ReactionsGate
    }

    fun reactionsGate(
        reactionsEnabled: Boolean,
        isSecretChat: Boolean?,
        secretReactionBlockEnabled: Boolean,
    ): ReactionsGate =
        when {
            !reactionsEnabled -> ReactionsGate.ReactionsDisabled
            isSecretChat == true && secretReactionBlockEnabled -> ReactionsGate.SecretReactionBlocked
            else -> ReactionsGate.Allowed
        }

    sealed interface GroupPlayGate {
        data object Allowed : GroupPlayGate
        data object NotGroup : GroupPlayGate
        data object GroupPlayDisabled : GroupPlayGate
    }

    fun groupPlayGate(isGroup: Boolean?, groupPlayEnabled: Boolean): GroupPlayGate =
        when {
            isGroup != true -> GroupPlayGate.NotGroup
            !groupPlayEnabled -> GroupPlayGate.GroupPlayDisabled
            else -> GroupPlayGate.Allowed
        }
}
