package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            colors = groupTextFieldColors(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.common_save)) }
    }
}
