package com.maodouchat.ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maodouchat.R

// 通话顶部网络质量小药丸（绿/黄/红）
@Composable
internal fun NetworkQualityPill(quality: NetworkQuality) {
    val (icon, color, label) = when (quality) {
        NetworkQuality.GOOD -> Triple(Icons.Outlined.SignalCellular4Bar, Color(0xFF34C759), R.string.call_network_good)
        NetworkQuality.FAIR -> Triple(Icons.Outlined.SignalCellularAlt, Color(0xFFFFC107), R.string.call_network_fair)
        NetworkQuality.POOR -> Triple(Icons.Outlined.SignalCellularConnectedNoInternet0Bar, Color(0xFFFF453A), R.string.call_network_poor)
        NetworkQuality.UNKNOWN -> Triple(Icons.Outlined.SignalCellularAlt, Color.White.copy(alpha = 0.55f), R.string.call_network_measuring)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelMedium)
    }
}
