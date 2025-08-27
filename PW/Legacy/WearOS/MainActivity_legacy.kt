package com.yourdomain.whisperworks

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.icons.Icons
import androidx.wear.compose.material.icons.filled.Hearing
import com.google.android.gms.wearable.Wearable
import com.yourdomain.whisperworks.theme.WhisperWorksTheme

private const val TAG = "WearApp"
private const val TRIGGER_PATH = "/whisper-works-trigger"

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            setContent {
                WearApp(isGranted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
fun WearApp(hasPermission: Boolean) {
    val context = LocalContext.current
    val audioTrigger = remember { AudioTrigger(context) }
    var statusText by remember { mutableStateOf("Initializing...") }

    DisposableEffect(hasPermission) {
        if (hasPermission) {
            statusText = "Listening..."
            audioTrigger.startListening {
                statusText = "Triggered! Sending signal..."
                sendMessageToPhone(context)
            }
        } else {
            statusText = "Microphone permission needed"
        }

        onDispose {
            audioTrigger.stopListening()
        }
    }

    WhisperWorksTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Hearing, contentDescription = "Listening Icon", modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.title3
            )
        }
    }
}

private fun sendMessageToPhone(context: Context) {
    val payload = "ACTIVATE_STT".toByteArray()
    val nodeClient = Wearable.getNodeClient(context)
    nodeClient.connectedNodes.addOnSuccessListener { nodes ->
        nodes.forEach { node ->
            val messageClient = Wearable.getMessageClient(context)
            messageClient.sendMessage(node.id, TRIGGER_PATH, payload)
                .addOnSuccessListener { Log.d(TAG, "Signal sent to ${node.displayName}") }
                .addOnFailureListener { e -> Log.e(TAG, "Signal send failed", e) }
        }
    }
}