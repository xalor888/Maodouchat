package com.maodouchat.ui.screen.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maodouchat.R
import com.maodouchat.webrtc.CallAudioRoute

internal fun audioRouteIcon(route: CallAudioRoute?) = when (route) {
    CallAudioRoute.BLUETOOTH -> Icons.Filled.BluetoothAudio
    CallAudioRoute.WIRED -> Icons.Filled.Headset
    CallAudioRoute.EARPIECE -> Icons.Filled.PhoneInTalk
    CallAudioRoute.SPEAKER, null -> Icons.AutoMirrored.Filled.VolumeUp
}

private fun audioRouteLabel(route: CallAudioRoute): Int = when (route) {
    CallAudioRoute.BLUETOOTH -> R.string.call_audio_route_bluetooth
    CallAudioRoute.WIRED -> R.string.call_audio_route_wired
    CallAudioRoute.EARPIECE -> R.string.call_audio_route_earpiece
    CallAudioRoute.SPEAKER -> R.string.call_audio_route_speaker
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallAudioRouteSheet(
    availableAudioRoutes: Set<CallAudioRoute>,
    selectedAudioRoute: CallAudioRoute?,
    onSelectAudioRoute: (CallAudioRoute) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            text = stringResource(R.string.call_choose_audio_route),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        CallAudioRoute.entries.filter { it in availableAudioRoutes }.forEach { route ->
            ListItem(
                headlineContent = { Text(stringResource(audioRouteLabel(route))) },
                leadingContent = { Icon(audioRouteIcon(route), contentDescription = null) },
                trailingContent = { RadioButton(selected = route == selectedAudioRoute, onClick = null) },
                modifier = Modifier.clickable {
                    onSelectAudioRoute(route)
                    onDismissRequest()
                }
            )
        }
        Spacer(modifier = Modifier.navigationBarsPadding().height(8.dp))
    }
}
