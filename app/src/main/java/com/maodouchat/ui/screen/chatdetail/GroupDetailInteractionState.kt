package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.maodouchat.group.GroupMemberUi

// 群详情页的对话框/搜索/展开交互状态簇：原在 GroupDetailScreen 内联声明，纯搬移不改判断。
// 调用方用 `var x by interaction.x` 本地委托接线（ExpressionStickerState 同款），下游读写零改动。
internal class GroupDetailInteractionState(
    val titleTarget: MutableState<GroupMemberUi?>,
    val removeTarget: MutableState<GroupMemberUi?>,
    val muteTarget: MutableState<GroupMemberUi?>,
    val showMuteAllConfirm: MutableState<Boolean>,
    val ownershipTarget: MutableState<GroupMemberUi?>,
    val showAvatarFull: MutableState<Boolean>,
    val showBotPicker: MutableState<Boolean>,
    val memberSearch: MutableState<String>,
    val memberSearchExpanded: MutableState<Boolean>,
    val membersExpanded: MutableState<Boolean>,
    val auditSearch: MutableState<String>,
    val auditSearchExpanded: MutableState<Boolean>,
    val auditExpanded: MutableState<Boolean>,
    val candidateSearch: MutableState<String>,
    val candidateSearchExpanded: MutableState<Boolean>,
    val candidatesExpanded: MutableState<Boolean>,
)

@Composable
internal fun rememberGroupDetailInteractionState(
    members: List<GroupMemberUi>,
): GroupDetailInteractionState {
    val interaction = GroupDetailInteractionState(
        titleTarget = remember { mutableStateOf<GroupMemberUi?>(null) },
        removeTarget = remember { mutableStateOf<GroupMemberUi?>(null) },
        muteTarget = remember { mutableStateOf<GroupMemberUi?>(null) },
        showMuteAllConfirm = remember { mutableStateOf(false) },
        ownershipTarget = remember { mutableStateOf<GroupMemberUi?>(null) },
        showAvatarFull = remember { mutableStateOf(false) },
        showBotPicker = rememberSaveable { mutableStateOf(false) },
        memberSearch = rememberSaveable { mutableStateOf("") },
        memberSearchExpanded = rememberSaveable { mutableStateOf(false) },
        membersExpanded = rememberSaveable { mutableStateOf(false) },
        auditSearch = rememberSaveable { mutableStateOf("") },
        auditSearchExpanded = rememberSaveable { mutableStateOf(false) },
        auditExpanded = rememberSaveable { mutableStateOf(false) },
        candidateSearch = rememberSaveable { mutableStateOf("") },
        candidateSearchExpanded = rememberSaveable { mutableStateOf(false) },
        candidatesExpanded = rememberSaveable { mutableStateOf(false) },
    )
    // 成员列表变化时清掉已不在成员中的对话框目标（原 LaunchedEffect(state.members)，键同一对象）。
    LaunchedEffect(members) {
        val ids = members.mapTo(hashSetOf()) { it.userId }
        if (interaction.titleTarget.value?.let { it.userId !in ids } == true) interaction.titleTarget.value = null
        if (interaction.removeTarget.value?.let { it.userId !in ids } == true) interaction.removeTarget.value = null
        if (interaction.muteTarget.value?.let { it.userId !in ids } == true) interaction.muteTarget.value = null
        if (interaction.ownershipTarget.value?.let { it.userId !in ids } == true) interaction.ownershipTarget.value = null
    }
    return interaction
}
