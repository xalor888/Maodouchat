package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.data.local.entity.AiTaskEntity
import com.maodouchat.ui.theme.LocalChatPalette
import java.text.DateFormat
import java.util.Date
// AI 任务的行组件簇：从 AiTasksScreen 拆出的同包行组件（筛选条/任务行/元信息/空状态）。
@Composable
internal fun AiTaskFilterStrip(
    selected: AiTaskFilter,
    onSelect: (AiTaskFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        AiTaskFilter.ALL to stringResource(R.string.ai_tasks_filter_all),
        AiTaskFilter.PENDING to stringResource(R.string.ai_tasks_filter_pending),
        AiTaskFilter.COMPLETED to stringResource(R.string.ai_tasks_filter_completed)
    )
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (filter, label) ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
internal fun AiTaskRow(
    task: AiTaskEntity,
    isMutating: Boolean,
    onCompletedChange: (Boolean) -> Unit,
    onAddToCalendar: () -> Unit,
    onDelete: () -> Unit
) {
    val rowColor by animateColorAsState(
        targetValue = if (task.isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "taskRowColor"
    )
    val formattedDueAt = remember(task.dueAt) {
        task.dueAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }
    }
    val dueLabel = remember(task.dueText, formattedDueAt) {
        listOfNotNull(task.dueText?.takeIf(String::isNotBlank), formattedDueAt)
            .distinct()
            .joinToString(" · ")
            .takeIf(String::isNotBlank)
    }
    val dueColor = if (!task.isCompleted && task.dueAt != null && task.dueAt < System.currentTimeMillis()) {
        MaterialTheme.colorScheme.error
    } else {
        LocalChatPalette.current.textSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .background(rowColor)
            .padding(start = 8.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        val impulse by animateFloatAsState(
            targetValue = if (task.isCompleted) 1.1f else 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 460f),
            label = "taskCheckScale"
        )
        Checkbox(
            checked = task.isCompleted,
            onCheckedChange = onCompletedChange,
            enabled = !isMutating,
            modifier = Modifier.graphicsLayer {
                scaleX = impulse
                scaleY = impulse
            }
        )
        Column(
            modifier = Modifier.weight(1f).padding(top = 3.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.isCompleted) LocalChatPalette.current.textHint else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Medium,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            task.owner?.takeIf(String::isNotBlank)?.let { owner ->
                AiTaskMetadata(
                    icon = { Icon(Icons.Outlined.PersonOutline, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    text = stringResource(R.string.ai_tasks_owner, owner),
                    color = LocalChatPalette.current.textSecondary
                )
            }
            dueLabel?.let { due ->
                AiTaskMetadata(
                    icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    text = stringResource(R.string.ai_tasks_due, due),
                    color = dueColor
                )
            }
        }
        if (isMutating) {
            Box(modifier = Modifier.size(if (task.dueAt != null) 80.dp else 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Row {
                if (task.dueAt != null) {
                    IconButton(onClick = onAddToCalendar, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = stringResource(R.string.ai_tasks_add_to_calendar),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.ai_tasks_delete),
                        tint = LocalChatPalette.current.textHint
                    )
                }
            }
        }
    }
}

internal fun openTaskInCalendar(context: Context, task: AiTaskEntity) {
    val dueAt = task.dueAt ?: return
    val description = buildString {
        task.owner?.takeIf(String::isNotBlank)?.let {
            append(context.getString(R.string.ai_tasks_owner, it))
        }
        task.sourceQuery.takeIf(String::isNotBlank)?.let {
            if (isNotEmpty()) append('\n')
            append(context.getString(R.string.ai_tasks_source, it))
        }
    }
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, task.title)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dueAt)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dueAt + 30 * 60_000L)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.ai_tasks_calendar_unavailable), Toast.LENGTH_SHORT).show()
        }
}

@Composable
internal fun AiTaskMetadata(
    icon: @Composable () -> Unit,
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides color) {
            icon()
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun AiTasksEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(Icons.Outlined.Checklist, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(44.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                stringResource(R.string.ai_tasks_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.ai_tasks_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalChatPalette.current.textHint
            )
        }
    }
}
