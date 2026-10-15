package com.maodouchat.ui.screen.groupplay

data class GroupChainUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val chains: List<ChainListItem> = emptyList(),
    val title: String = "",
    val topic: String = "",
    val creating: Boolean = false,
    val detail: GroupChainDetailState? = null,
    val entryInput: String = ""
)

data class GroupChainDetailState(
    val chainId: String,
    val title: String,
    val topic: String,
    val active: Boolean,
    val entryCount: Int,
    val maxEntries: Int,
    val myJoined: Boolean,
    val entries: List<ChainEntryItem>,
    val submitting: Boolean = false
)

data class GroupCheckinUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val checkedIn: Boolean = false,
    val streak: Int = 0,
    val totalCount: Int = 0,
    val todayRank: Int = 0,
    val todayCount: Int = 0,
    val ranking: List<CheckinRankItem> = emptyList(),
    val checking: Boolean = false
)

data class GroupPkUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val pks: List<PkListItem> = emptyList(),
    val leftTitle: String = "",
    val rightTitle: String = "",
    val creating: Boolean = false,
    val votingPkId: String? = null
)

data class GroupPollUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val polls: List<PollListItem> = emptyList(),
    val question: String = "",
    val options: MutableList<String> = mutableListOf("", ""),
    val multi: Boolean = false,
    val anonymous: Boolean = false,
    val creating: Boolean = false,
    val createdShare: String? = null,
    val votingPollId: String? = null
)
