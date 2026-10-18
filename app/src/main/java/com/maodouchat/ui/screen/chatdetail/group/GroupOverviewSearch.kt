package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R

@Composable
fun SearchableSectionHeader(
    text: String,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true,
    trailingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        if (showSearch) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(
                        if (searchExpanded) R.string.chat_search_close else R.string.chat_search_action
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        trailingContent()
    }
}

@Composable
fun CollapsibleGroupSearchField(
    visible: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        TextField(
            value = value,
            onValueChange = { onValueChange(it.take(120)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(placeholder) },
            modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            colors = groupTextFieldColors(),
            shape = RoundedCornerShape(10.dp)
        )
    }
}
