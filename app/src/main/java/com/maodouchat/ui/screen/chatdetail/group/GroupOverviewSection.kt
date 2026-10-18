package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.screen.chatdetail.group.GroupMemberSectionUtils.roleLabel
import com.maodouchat.ui.theme.LocalChatPalette

@Composable
fun GroupHeader(
    groupName: String,
    groupAvatar: String?,
    memberCount: Int,
    myRole: String,
    modifier: Modifier = Modifier,
    onShowAvatarFull: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Avatar(
                name = groupName,
                avatarUrl = groupAvatar,
                size = AvatarSize.LG,
                modifier = Modifier.clickable(enabled = !groupAvatar.isNullOrBlank(), onClick = onShowAvatarFull)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = groupName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = pluralStringResource(R.plurals.group_detail_header_summary, memberCount, memberCount, roleLabel(myRole)),
            style = MaterialTheme.typography.bodySmall,
            color = LocalChatPalette.current.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GroupFeaturesCard(
    actions: List<GroupFeatureAction>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        actions.chunked(2).forEachIndexed { rowIndex, rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupFeatureButton(rowActions[0], Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                )
                if (rowActions.size == 2) {
                    GroupFeatureButton(rowActions[1], Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowIndex < actions.chunked(2).lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            }
        }
    }
}

@Composable
fun GroupFeatureButton(action: GroupFeatureAction, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(104.dp)
            .clickable(onClick = action.onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun groupTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LocalChatPalette.current.chatInputBackground,
    unfocusedContainerColor = LocalChatPalette.current.chatInputBackground,
    disabledContainerColor = LocalChatPalette.current.chatInputBackground,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
    disabledIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = LocalChatPalette.current.textHint
)
