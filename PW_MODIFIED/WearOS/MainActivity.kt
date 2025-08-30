package com.yourdomain.whisperworks.wear.presentation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import androidx.wear.compose.material.icons.Icons
import androidx.wear.compose.material.icons.filled.*
import com.yourdomain.whisperworks.wear.ui.theme.WhisperWorksWearTheme
import com.yourdomain.whisperworks.wear.domain.audio.AudioTriggerState
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Permission launcher for microphone access
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val vibrationGranted = permissions[Manifest.permission.VIBRATE] ?: true

        if (microphoneGranted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("WearOS MainActivity created")

        // Request necessary permissions
        requestPermissions()

        setContent {
            WhisperWorksWearTheme {
                WearApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    /**
     * Request necessary permissions for the app
     */
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE
        )
        permissionLauncher.launch(permissions)
    }
}

@Composable
fun WearApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Handle permission dialog
    if (uiState.showPermissionDialog) {
        PermissionDialog(
            onGrantPermission = { /* Navigate to settings */ },
            onDismiss = { viewModel.dismissPermissionDialog() }
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Status Display
            StatusDisplay(
                triggerState = uiState.triggerState,
                connectionStatus = uiState.connectionStatus,
                audioLevel = uiState.audioLevel,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Control Buttons
            ControlButtons(
                triggerState = uiState.triggerState,
                isPermissionGranted = uiState.hasPermission,
                onStartListening = { viewModel.startListening() },
                onStopListening = { viewModel.stopListening() },
                onSendTrigger = { viewModel.sendManualTrigger() },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Connection Indicator
            ConnectionIndicator(
                connectionStatus = uiState.connectionStatus,
                connectedDevices = uiState.connectedDevices,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun StatusDisplay(
    triggerState: AudioTriggerState,
    connectionStatus: WearableConnectionStatus,
    audioLevel: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = CircleShape
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Status Icon
            Icon(
                imageVector = when (triggerState) {
                    AudioTriggerState.LISTENING -> Icons.Default.Hearing
                    AudioTriggerState.STOPPING -> Icons.Default.Stop
                    AudioTriggerState.ERROR -> Icons.Default.Error
                    else -> Icons.Default.MicOff
                },
                contentDescription = "Status",
                modifier = Modifier.size(32.dp),
                tint = when (triggerState) {
                    AudioTriggerState.LISTENING -> Color.Green
                    AudioTriggerState.ERROR -> Color.Red
                    else -> MaterialTheme.colors.onSurface
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status Text
            Text(
                text = getStatusText(triggerState, connectionStatus),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface
            )

            // Audio Level Indicator
            if (triggerState == AudioTriggerState.LISTENING && audioLevel > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                AudioLevelIndicator(
                    level = audioLevel,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ControlButtons(
    triggerState: AudioTriggerState,
    isPermissionGranted: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSendTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Listen Toggle Button
        Button(
            onClick = {
                when (triggerState) {
                    AudioTriggerState.LISTENING -> onStopListening()
                    else -> onStartListening()
                }
            },
            enabled = isPermissionGranted && triggerState != AudioTriggerState.STOPPING,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (triggerState == AudioTriggerState.LISTENING)
                    Color.Red else MaterialTheme.colors.primary
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (triggerState == AudioTriggerState.LISTENING)
                    Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (triggerState == AudioTriggerState.LISTENING)
                    "Stop" else "Start",
                modifier = Modifier.size(20.dp)
            )
        }

        // Manual Trigger Button
        Button(
            onClick = onSendTrigger,
            enabled = isPermissionGranted,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.secondary
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send Trigger",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun AudioLevelIndicator(
    level: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(10) { index ->
            val isActive = level * 10 > index
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(if (isActive) 12.dp else 4.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) {
                            when {
                                level > 0.8f -> Color.Red
                                level > 0.5f -> Color.Yellow
                                else -> Color.Green
                            }
                        } else {
                            MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                        }
                    )
            )
        }
    }
}

@Composable
fun ConnectionIndicator(
    connectionStatus: WearableConnectionStatus,
    connectedDevices: List<String>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        backgroundColor = when (connectionStatus) {
            WearableConnectionStatus.CONNECTED -> Color.Green.copy(alpha = 0.2f)
            WearableConnectionStatus.CONNECTING -> Color.Yellow.copy(alpha = 0.2f)
            else -> Color.Red.copy(alpha = 0.2f)
        }
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Connection Status Icon
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (connectionStatus) {
                            WearableConnectionStatus.CONNECTED -> Color.Green
                            WearableConnectionStatus.CONNECTING -> Color.Yellow
                            else -> Color.Red
                        }
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Connection Status Text
            Text(
                text = when (connectionStatus) {
                    WearableConnectionStatus.CONNECTED -> "Connected (${connectedDevices.size})"
                    WearableConnectionStatus.CONNECTING -> "Connecting..."
                    else -> "Disconnected"
                },
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface
            )
        }
    }
}

@Composable
fun PermissionDialog(
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Permission Required",
                style = MaterialTheme.typography.title3
            )
        },
        message = {
            Text(
                "Microphone access is needed to detect voice triggers.",
                style = MaterialTheme.typography.body2
            )
        },
        confirm = {
            Button(
                onClick = onGrantPermission,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary
                )
            ) {
                Text("Settings")
            }
        },
        dismiss = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface
                )
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Helper function to get status text
 */
private fun getStatusText(
    triggerState: AudioTriggerState,
    connectionStatus: WearableConnectionStatus
): String {
    return when {
        triggerState == AudioTriggerState.ERROR -> "Error"
        triggerState == AudioTriggerState.LISTENING -> "Listening..."
        triggerState == AudioTriggerState.STOPPING -> "Stopping..."
        connectionStatus != WearableConnectionStatus.CONNECTED -> "No Phone"
        else -> "Ready"
    }
}

/**
 * Enum for wearable connection status
 */
enum class WearableConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
