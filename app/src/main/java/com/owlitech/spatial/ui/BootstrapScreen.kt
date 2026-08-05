package com.owlitech.spatial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owlitech.spatial.R
import com.owlitech.spatial.ar.ArCapability

@Composable
fun BootstrapScreen(
    capability: ArCapability,
    cameraPermissionGranted: Boolean,
    onCheckAgain: () -> Unit,
    onRequestCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.bootstrap_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.bootstrap_subtitle),
            style = MaterialTheme.typography.bodyLarge,
        )

        StatusCard(
            label = stringResource(R.string.arcore_label),
            value = stringResource(capabilityTextResource(capability)),
        )
        StatusCard(
            label = stringResource(R.string.camera_label),
            value = stringResource(
                if (cameraPermissionGranted) R.string.camera_granted else R.string.camera_missing,
            ),
        )
        StatusCard(
            label = stringResource(R.string.mapping_label),
            value = stringResource(R.string.mapping_not_started),
            explanation = stringResource(R.string.mapping_explanation),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onCheckAgain, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.check_again))
            }
            if (!cameraPermissionGranted) {
                Button(onClick = onRequestCamera, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.request_camera))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.privacy_notice),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.privacy_text),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusCard(
    label: String,
    value: String,
    explanation: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge)
            if (explanation != null) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
