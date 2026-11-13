package com.maodouchat.ui.screen.explore

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.core.model.NearbyPerson
import com.maodouchat.domain.contacts.NearbyDistanceLabel
import com.maodouchat.domain.contacts.nearbyDistanceLabel
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette

/** 「附近的人」行组件簇（从 ExploreNearbyScreen.kt 纯搬移；NearbyScreen 留母文件）。 */
@Composable
internal fun NearbyItem(person: NearbyPerson, onClick: () -> Unit) {
    val user = person.user
    val distanceText = if (user.isOnline) {
        stringResource(R.string.explore_nearby_distance_online, formatNearbyDistance(person.distanceMeters))
    } else {
        formatNearbyDistance(person.distanceMeters)
    }
    val updatedText = if (person.locationUpdatedAt > 0L) {
        val relative = DateUtils.getRelativeTimeSpanString(
            person.locationUpdatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
        stringResource(R.string.explore_nearby_updated, relative)
    } else {
        null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = user.name, avatarUrl = user.avatar, size = AvatarSize.MD, isOnline = user.isOnline)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (user.isOnline) MaterialTheme.colorScheme.primary else LocalChatPalette.current.textSecondary
                )
                if (updatedText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = updatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatPalette.current.textHint
                    )
                }
            }
            Icon(Icons.Outlined.NearMe, contentDescription = null, tint = LocalChatPalette.current.textHint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun formatNearbyDistance(distanceMeters: Int): String =
    when (val label = nearbyDistanceLabel(distanceMeters)) {
        is NearbyDistanceLabel.Meters ->
            stringResource(R.string.explore_nearby_distance_meters, label.meters)
        is NearbyDistanceLabel.Kilometers ->
            stringResource(R.string.explore_nearby_distance_km, label.formattedKm)
    }
