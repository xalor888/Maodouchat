package com.maodouchat.ui.screen.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.component.SearchBar

@Composable
internal fun ExploreFeedHeader(
    feedSearch: String,
    onFeedSearchChange: (String) -> Unit,
    focusRequester: FocusRequester,
    showOnlyMine: Boolean,
    onToggleOnlyMine: () -> Unit,
    showOnlyMedia: Boolean,
    onToggleOnlyMedia: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchBar(
            value = feedSearch,
            onValueChange = { onFeedSearchChange(it.take(160)) },
            placeholder = stringResource(R.string.explore_feed_search_hint),
            modifier = Modifier.fillMaxWidth(),
            focusRequester = focusRequester
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = showOnlyMine,
                onClick = onToggleOnlyMine,
                label = { Text(stringResource(R.string.explore_feed_only_mine)) }
            )
            FilterChip(
                selected = showOnlyMedia,
                onClick = onToggleOnlyMedia,
                label = { Text(stringResource(R.string.explore_feed_only_media)) }
            )
        }
    }
}
