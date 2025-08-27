package com.yourdomain.whisperworks

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yourdomain.whisperworks.ui.theme.WhisperWorksTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted
            } else {
                // Handle permission denial
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            WhisperWorksTheme {
                AudioCaptureScreen()
            }
        }
    }
}

@Composable
fun AudioCaptureScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioRecorder = remember { AudioRecorder(context) }

    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Press START to begin") }
    var transcriptionResult by remember { mutableStateOf<TranscriptionResponse?>(null) }
    var autoRecord by remember { mutableStateOf(false) }

    // Listener for triggers from the Wear OS device
    DisposableEffect(context) {
        val filter = IntentFilter("com.yourdomain.whisperworks.MESSAGE_RECEIVED")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isRecording) {
                    autoRecord = true // Set a flag to start recording
                }
            }
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    // Effect to start recording when the flag is set
    LaunchedEffect(autoRecord) {
        if (autoRecord && !isRecording) {
            audioRecorder.startRecording()
            isRecording = true
            statusText = "Recording... (Triggered by Watch)"
            autoRecord = false // Reset the flag
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Button(
                onClick = {
                    if (!isRecording) {
                        transcriptionResult = null
                        audioRecorder.startRecording()
                        isRecording = true
                        statusText = "Recording..."
                    } else {
                        isRecording = false
                        statusText = "Processing..."
                        coroutineScope.launch {
                            val audioFile = audioRecorder.stopRecording()
                            if (audioFile != null) {
                                statusText = "Uploading and transcribing..."
                                val result = NetworkClient.uploadAndTranscribe(audioFile)
                                result.onSuccess { response ->
                                    transcriptionResult = response
                                    statusText = "Success!"
                                }.onFailure { error ->
                                    transcriptionResult = null
                                    statusText = "Error: ${error.message}"
                                }
                            } else {
                                transcriptionResult = null
                                statusText = "Recording failed"
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = !isRecording
            ) {
                Text(
                    if (isRecording) "STOP RECORDING" else "START RECORDING",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (transcriptionResult != null) {
                    HighlightedText(
                        fullText = transcriptionResult!!.full_text,
                        keywords = transcriptionResult!!.keywords
                    )
                } else {
                    Text(
                        text = statusText,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }

            Text(
                text = if (isRecording) "Status: Recording..." else "Status: Idle",
                modifier = Modifier.padding(bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun HighlightedText(fullText: String, keywords: List<String>, modifier: Modifier = Modifier) {
    val keywordSet = keywords.toSet()
    val annotatedString = buildAnnotatedString {
        val words = fullText.split(' ')
        words.forEachIndexed { index, word ->
            if (word in keywordSet) {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)) {
                    append(word)
                }
            } else {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onBackground)) {
                    append(word)
                }
            }
            if (index < words.size - 1) append(" ")
        }
    }
    Text(
        text = annotatedString,
        fontSize = 28.sp,
        lineHeight = 40.sp,
        modifier = modifier
    )
}