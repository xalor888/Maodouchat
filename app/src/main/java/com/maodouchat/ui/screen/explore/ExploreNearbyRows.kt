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
import com.maodouchat.ui.component.Avatar
import com.maodouchat.ui.component.AvatarSize
import com.maodouchat.ui.theme.LocalChatPalette
import java.text.NumberFormat

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
private fun formatNearbyDistance(distanceMeters: Int): String {
    val (res, arg) = nearbyDistanceLabel(distanceMeters)
    return if (arg == null) stringResource(res) else stringResource(res, arg)
}

// NumberFormat 非线程安全，ThreadLocal 每线程复用一个。
// 半径选项的小数位随选项变化，每次调用前重设；距离文案固定一位小数。
internal val nearbyRadiusFormat: ThreadLocal<NumberFormat> =
    ThreadLocal.withInitial { NumberFormat.getNumberInstance() }
private val nearbyDistanceFormat: ThreadLocal<NumberFormat> =
    ThreadLocal.withInitial {
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }

/**
 * 距离 → (文案资源, 实参)（G163 从 `formatNearbyDistance` 抽出的纯判定）。
 *
 * 抽开之前这段和 `stringResource` 耦在一起，只能靠仪器测试覆盖；
 * 现在返回二元组，普通 JVM 单测就能逐分支断言。
 *
 * 两个细节都有产品理由，改时必须连测试一起改：
 * - **米那一支 `coerceAtLeast(100)`**：定位精度有限，显示「约 3 米」比「约 0 米」更可信；
 * - **公里那一支固定一位小数**：`NumberFormat` 默认对 2.0 显示 "2"，
 *   加上 `minimumFractionDigits = 1` 才会显示 "2.0"，与米的精度观感一致。
 */
internal fun nearbyDistanceLabel(distanceMeters: Int): Pair<Int, Any?> =
    if (distanceMeters < 1_000) {
        R.string.explore_nearby_distance_meters to distanceMeters.coerceAtLeast(100)
    } else {
        R.string.explore_nearby_distance_km to nearbyDistanceFormat.get().format(distanceMeters / 1_000.0)
    }
