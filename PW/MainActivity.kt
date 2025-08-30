package com.yourdomain.whisperworks.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yourdomain.whisperworks.data.network.NetworkProgress
import com.yourdomain.whisperworks.data.network.TranscriptionResponse
import com.yourdomain.whisperworks.domain.audio.RecordingState
import com.yourdomain.whisperworks.services.DataLayerListenerService
import com.yourdomain.whisperworks.ui.theme.WhisperWorksTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Permission launcher for microphone
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        } else true

        if (microphoneGranted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestNecessaryPermissions()
        
        setContent {
            WhisperWorksTheme {
                AudioCaptureScreenV2(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerWearableReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterWearableReceiver()
    }

    /**
     * Request necessary permissions
     */
    private fun requestNecessaryPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    /**
     * Register broadcast receiver for wearable messages
     */
    private fun registerWearableReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(DataLayerListenerService.ACTION_START_RECORDING)
            addAction(DataLayerListenerService.ACTION_STOP_RECORDING)
            addAction(DataLayerListenerService.ACTION_STATUS_REQUEST)
        }
        
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(wearableReceiver, intentFilter)
    }

    /**
     * Unregister broadcast receiver
     */
    private fun unregisterWearableReceiver() {
        try {
            LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(wearableReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver wasn't registered
        }
    }

    /**
     * Broadcast receiver for wearable device messages
     */
    private val wearableReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DataLayerListenerService.ACTION_START_RECORDING -> {
                    val nodeId = intent.getStringExtra(DataLayerListenerService.EXTRA_NODE_ID)
                    viewModel.startRecordingFromWearable(nodeId)
                }
                DataLayerListenerService.ACTION_STOP_RECORDING -> {
                    viewModel.stopRecording()
                }
                DataLayerListenerService.ACTION_STATUS_REQUEST -> {
                    // Handle status request if needed
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCaptureScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Show permission dialog if needed
    if (uiState.showPermissionDialog) {
        PermissionDialog(
            onRequestPermission = {
                ActivityCompat.requestPermissions(
                    context as ComponentActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    0
                )
            },
            onDismiss = { viewModel.dismissPermissionDialog() }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "WhisperWorks",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                actions = {
                    IconButton(onClick = { /* Open settings */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // Status Card
            StatusCard(
                recordingState = uiState.recordingState,
                audioLevel = uiState.audioLevel,
                networkProgress = uiState.networkProgress,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Transcription Result
            TranscriptionResultCard(
                transcriptionResult = uiState.transcriptionResult,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Audio Level Indicator
            if (uiState.recordingState == RecordingState.RECORDING) {
                AudioLevelIndicator(
                    level = uiState.audioLevel,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            
            // Record Button
            RecordButton(
                recordingState = uiState.recordingState,
                isEnabled = uiState.isRecordButtonEnabled,
                onStartRecording = { 
                    coroutineScope.launch {
                        viewModel.startRecording() 
                    }
                },
                onStopRecording = { 
                    coroutineScope.launch {
                        viewModel.stopRecording() 
                    }
                },
                modifier = Modifier.size(80.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Connection Status
            ConnectionStatusCard(
                isConnected = uiState.isWearableConnected,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun StatusCard(
    recordingState: RecordingState,
    audioLevel: Float,
    networkProgress: NetworkProgress?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = getStatusText(recordingState, networkProgress),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            
            if (networkProgress is NetworkProgress.Uploading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = networkProgress.progress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${networkProgress.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun TranscriptionResultCard(
    transcriptionResult: TranscriptionResponse?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (transcriptionResult != null) {
                HighlightedText(
                    fullText = transcriptionResult.full_text,
                    keywords = transcriptionResult.keywords
                )
            } else {
                Text(
                    text = "Press the microphone to start recording",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
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
        repeat(20) { index ->
            val barHeight = if (level * 20 > index) 16.dp else 4.dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .background(
                        color = if (level * 20 > index) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(1.5.dp)
                    )
            )
        }
    }
}

@Composable
fun RecordButton(
    recordingState: RecordingState,
    isEnabled: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = recordingState == RecordingState.RECORDING
    
    FloatingActionButton(
        onClick = {
            if (isRecording) {
                onStopRecording()
            } else {
                onStartRecording()
            }
        },
        modifier = modifier,
        containerColor = if (isRecording) 
            MaterialTheme.colorScheme.error 
        else 
            MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        )
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.error
                    )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = if (isConnected) "Wearable Connected" else "Wearable Disconnected",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun HighlightedText(
    fullText: String,
    keywords: List<String>,
    modifier: Modifier = Modifier
) {
    val keywordSet = keywords.toSet()
    val annotatedString = buildAnnotatedString {
        val words = fullText.split(' ')
        words.forEachIndexed { index, word ->
            if (word.lowercase() in keywordSet.map { it.lowercase() }) {
                withStyle(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold
                    )
                ) {
                    append(word)
                }
            } else {
                withStyle(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    append(word)
                }
            }
            if (index < words.size - 1) append(" ")
        }
    }
    
    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 28.sp,
        modifier = modifier
    )
}

@Composable
fun PermissionDialog(
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Microphone Permission Required") },
        text = { 
            Text("This app needs microphone permission to record audio for transcription. Please grant the permission to continue.") 
        },
        confirmButton = {
            TextButton(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Helper function to get status text based on current state
 */
private fun getStatusText(recordingState: RecordingState, networkProgress: NetworkProgress?): String {
    return when {
        networkProgress is NetworkProgress.Uploading -> "Uploading..."
        networkProgress is NetworkProgress.Processing -> "Processing..."
        networkProgress is NetworkProgress.Error -> "Error: ${networkProgress.exception.message}"
        recordingState == RecordingState.RECORDING -> "Recording..."
        recordingState == RecordingState.STOPPING -> "Stopping..."
        recordingState == RecordingState.COMPLETED -> "Recording Complete"
        recordingState == RecordingState.ERROR -> "Recording Error"
        else -> "Ready to Record"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("whisper_works_prefs", Context.MODE_PRIVATE)
    }
    var serverIp by remember {
        mutableStateOf(sharedPreferences.getString("server_ip", "http://192.168.1.100:8000") ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Enter the IP address of your WhisperWorks backend server. It should include the protocol and port (e.g., http://192.168.1.10:8000).",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            OutlinedTextField(
                value = serverIp,
                onValueChange = { serverIp = it },
                label = { Text("Server IP Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = {
                    sharedPreferences.edit().putString("server_ip", serverIp.trim()).apply()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCaptureScreenV2(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showSettingsScreen by remember { mutableStateOf(false) }

    if (showSettingsScreen) {
        SettingsScreen(onDismiss = { showSettingsScreen = false })
        return
    }

    // Show permission dialog if needed
    if (uiState.showPermissionDialog) {
        PermissionDialog(
            onRequestPermission = {
                ActivityCompat.requestPermissions(
                    context as ComponentActivity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    0
                )
            },
            onDismiss = { viewModel.dismissPermissionDialog() }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "WhisperWorks",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showSettingsScreen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Status Card
            StatusCard(
                recordingState = uiState.recordingState,
                audioLevel = uiState.audioLevel,
                networkProgress = uiState.networkProgress,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Transcription Result
            TranscriptionResultCard(
                transcriptionResult = uiState.transcriptionResult,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Audio Level Indicator
            if (uiState.recordingState == RecordingState.RECORDING) {
                AudioLevelIndicator(
                    level = uiState.audioLevel,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }

            // Record Button
            RecordButton(
                recordingState = uiState.recordingState,
                isEnabled = uiState.isRecordButtonEnabled,
                onStartRecording = {
                    coroutineScope.launch {
                        viewModel.startRecording()
                    }
                },
                onStopRecording = {
                    coroutineScope.launch {
                        viewModel.stopRecording()
                    }
                },
                modifier = Modifier.size(80.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connection Status
            ConnectionStatusCard(
                isConnected = uiState.isWearableConnected,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}