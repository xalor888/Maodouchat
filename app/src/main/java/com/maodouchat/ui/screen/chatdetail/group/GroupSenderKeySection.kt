package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.crypto.SenderKeyCoveragePolicy
import com.maodouchat.network.SenderKeyDistributionStatusDto
import com.maodouchat.group.GroupMemberUi
import com.maodouchat.ui.theme.LocalChatPalette
import com.maodouchat.ui.theme.UnreadRed

private const val SENDER_KEY_TARGET_PAGE = 20

enum class SenderKeyTargetFilter { ALL, FAILED, PENDING, SENT }

@Composable
fun GroupSenderKeyStatusSection(
    status: SenderKeyDistributionStatusDto?,
    memberRevision: Long,
    members: List<GroupMemberUi>,
    isUpdating: Boolean,
    hasLocalDistribution: Boolean?,
    onRedistribute: () -> Unit
) {
    var targetFilter by rememberSaveable { mutableStateOf(SenderKeyTargetFilter.ALL) }
    var targetsExpanded by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.group_detail_sender_key))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val targetStatuses = status?.targets?.map { it.status }.orEmpty()
        val assessment = remember(status, memberRevision, targetStatuses) {
            SenderKeyCoveragePolicy.assess(
                hasLocalDistribution = hasLocalDistribution ?: (status != null && status.total > 0),
                requestedEpoch = memberRevision,
                statusEpoch = status?.epoch ?: 0L,
                targetStatuses = targetStatuses,
                reportedTotal = status?.total
            )
        }
        if (status == null || status.total == 0) {
            Text(
                stringResource(R.string.group_detail_no_key_record),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textHint
            )
            Text(
                stringResource(R.string.group_detail_key_auto_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary
            )
            Text(
                senderKeyReasonLabel(assessment.reason),
                style = MaterialTheme.typography.labelSmall,
                color = LocalChatPalette.current.textSecondary
            )
            Button(onClick = onRedistribute, enabled = !isUpdating) {
                Text(stringResource(R.string.group_detail_distribute_now))
            }
            return@Column
        }
        val needsAction = assessment.requiresDistribution
        val filteredTargets = remember(status.targets, targetFilter) {
            when (targetFilter) {
                SenderKeyTargetFilter.ALL -> status.targets
                SenderKeyTargetFilter.FAILED -> status.targets.filter { it.status.equals("FAILED", ignoreCase = true) }
                SenderKeyTargetFilter.PENDING -> status.targets.filter { it.status.equals("PENDING", ignoreCase = true) }
                SenderKeyTargetFilter.SENT -> status.targets.filter { it.status.equals("SENT", ignoreCase = true) }
            }
        }
        val visibleTargets = remember(filteredTargets, targetsExpanded) {
            if (targetsExpanded || filteredTargets.size <= SENDER_KEY_TARGET_PAGE) {
                filteredTargets
            } else {
                filteredTargets.take(SENDER_KEY_TARGET_PAGE)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.group_detail_epoch, status.epoch),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    pluralStringResource(
                        R.plurals.group_detail_device_stats,
                        status.total,
                        status.total,
                        status.sent,
                        status.failed,
                        status.pending
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalChatPalette.current.textSecondary
                )
                Text(
                    senderKeyReasonLabel(assessment.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (needsAction) UnreadRed else LocalChatPalette.current.textSecondary
                )
            }
            Text(
                if (needsAction) stringResource(R.string.group_detail_retry_needed) else stringResource(R.string.group_detail_status_normal),
                style = MaterialTheme.typography.labelLarge,
                color = if (needsAction) UnreadRed else MaterialTheme.colorScheme.primary
            )
        }
        Button(onClick = onRedistribute, enabled = !isUpdating, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (assessment.reason) {
                    SenderKeyCoveragePolicy.Reason.FAILED_TARGETS ->
                        stringResource(R.string.group_detail_retry_failed_devices)
                    SenderKeyCoveragePolicy.Reason.PENDING_TARGETS ->
                        stringResource(R.string.group_detail_retry_pending_devices)
                    else -> stringResource(R.string.group_detail_redistribute_key)
                }
            )
        }
        if (status.targets.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SenderKeyTargetFilter.entries.forEach { filter ->
                    val selected = targetFilter == filter
                    TextButton(
                        onClick = {
                            targetFilter = filter
                            targetsExpanded = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when (filter) {
                                SenderKeyTargetFilter.ALL -> stringResource(R.string.group_detail_key_filter_all)
                                SenderKeyTargetFilter.FAILED -> stringResource(R.string.group_detail_key_filter_failed)
                                SenderKeyTargetFilter.PENDING -> stringResource(R.string.group_detail_key_filter_pending)
                                SenderKeyTargetFilter.SENT -> stringResource(R.string.group_detail_key_filter_sent)
                            },
                            color = if (selected) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        if (filteredTargets.isEmpty()) {
            Text(
                stringResource(R.string.group_detail_key_filter_empty),
                style = MaterialTheme.typography.bodySmall,
                color = LocalChatPalette.current.textHint
            )
        } else {
            visibleTargets.forEach { target ->
                val memberName = members.firstOrNull { it.userId == target.userId }?.displayName ?: target.userId
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.group_detail_device, memberName, target.deviceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        senderKeyStatusLabel(target.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = senderKeyStatusColor(target.status)
                    )
                }
            }
            if (filteredTargets.size > SENDER_KEY_TARGET_PAGE) {
                TextButton(
                    onClick = { targetsExpanded = !targetsExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (targetsExpanded) {
                            stringResource(R.string.chat_transcript_collapse)
                        } else {
                            stringResource(R.string.group_detail_more_devices, filteredTargets.size - SENDER_KEY_TARGET_PAGE)
                        },
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun senderKeyReasonLabel(reason: SenderKeyCoveragePolicy.Reason): String =
    stringResource(
        when (reason) {
            SenderKeyCoveragePolicy.Reason.LOCAL_MISSING ->
                R.string.group_detail_key_reason_local_missing
            SenderKeyCoveragePolicy.Reason.EPOCH_MISMATCH ->
                R.string.group_detail_key_reason_epoch_mismatch
            SenderKeyCoveragePolicy.Reason.FAILED_TARGETS ->
                R.string.group_detail_key_reason_failed
            SenderKeyCoveragePolicy.Reason.PENDING_TARGETS ->
                R.string.group_detail_key_reason_pending
            SenderKeyCoveragePolicy.Reason.UNKNOWN_TARGETS ->
                R.string.group_detail_key_reason_unknown
            SenderKeyCoveragePolicy.Reason.NO_SERVER_RECORD ->
                R.string.group_detail_key_reason_no_record
            SenderKeyCoveragePolicy.Reason.COMPLETE ->
                R.string.group_detail_key_reason_complete
        }
    )

@Composable
fun senderKeyStatusLabel(status: String): String = when (status.uppercase()) {
    "SENT" -> stringResource(R.string.group_detail_key_sent)
    "FAILED" -> stringResource(R.string.group_detail_key_failed)
    "PENDING" -> stringResource(R.string.group_detail_key_pending)
    else -> status
}

@Composable
fun senderKeyStatusColor(status: String) = when (status.uppercase()) {
    "SENT" -> MaterialTheme.colorScheme.primary
    "FAILED" -> UnreadRed
    else -> LocalChatPalette.current.textHint
}
