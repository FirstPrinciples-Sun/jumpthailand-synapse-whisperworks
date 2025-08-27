package com.yourdomain.whisperworks.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourdomain.whisperworks.data.network.NetworkClient
import com.yourdomain.whisperworks.data.network.NetworkProgress
import com.yourdomain.whisperworks.data.network.TranscriptionResponse
import com.yourdomain.whisperworks.domain.audio.AudioRecorder
import com.yourdomain.whisperworks.domain.audio.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

/**
 * ViewModel for managing audio recording and transcription state
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val networkClient: NetworkClient
) : ViewModel() {
    
    companion object {
        private const val TAG = "MainViewModel"
        private const val DEFAULT_SERVER_URL = "http://192.168.1.100:8000" // Replace with your server
    }

    // Internal mutable state
    private val _uiState = MutableStateFlow(AudioCaptureUiState())
    val uiState: StateFlow<AudioCaptureUiState> = _uiState.asStateFlow()
    
    private val _networkProgress = MutableStateFlow<NetworkProgress?>(null)
    private val _transcriptionResult = MutableStateFlow<TranscriptionResponse?>(null)
    private val _isWearableConnected = MutableStateFlow(false)
    private val _showPermissionDialog = MutableStateFlow(false)
    private val _hasPermission = MutableStateFlow(false)

    init {
        // Observe state changes and update UI state
        viewModelScope.launch {
            combine(
                audioRecorder.recordingState,
                audioRecorder.audioLevel,
                _networkProgress,
                _transcriptionResult,
                _isWearableConnected,
                _showPermissionDialog,
                _hasPermission
            ) { recordingState, audioLevel, networkProgress, transcriptionResult, 
                isWearableConnected, showPermissionDialog, hasPermission ->
                
                AudioCaptureUiState(
                    recordingState = recordingState,
                    audioLevel = audioLevel,
                    networkProgress = networkProgress,
                    transcriptionResult = transcriptionResult,
                    isWearableConnected = isWearableConnected,
                    showPermissionDialog = showPermissionDialog,
                    isRecordButtonEnabled = hasPermission && 
                        (recordingState == RecordingState.IDLE || recordingState == RecordingState.RECORDING)
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    /**
     * Start recording audio
     */
    suspend fun startRecording() {
        if (!_hasPermission.value) {
            _showPermissionDialog.value = true
            return
        }

        Log.d(TAG, "Starting audio recording")
        
        // Clear previous results
        _transcriptionResult.value = null
        _networkProgress.value = null
        
        val result = audioRecorder.startRecording()
        result.onFailure { exception ->
            Log.e(TAG, "Failed to start recording", exception)
            handleRecordingError(exception)
        }
    }

    /**
     * Stop recording and process transcription
     */
    suspend fun stopRecording() {
        Log.d(TAG, "Stopping audio recording")
        
        val result = audioRecorder.stopRecording()
        result.fold(
            onSuccess = { audioFile ->
                Log.d(TAG, "Recording stopped successfully, starting transcription")
                processTranscription(audioFile)
            },
            onFailure = { exception ->
                Log.e(TAG, "Failed to stop recording", exception)
                handleRecordingError(exception)
            }
        )
    }

    /**
     * Start recording triggered from wearable device
     */
    fun startRecordingFromWearable(nodeId: String?) {
        Log.d(TAG, "Recording triggered from wearable: $nodeId")
        
        viewModelScope.launch {
            startRecording()
        }
    }

    /**
     * Process transcription using network client
     */
    private fun processTranscription(audioFile: java.io.File) {
        viewModelScope.launch {
            try {
                networkClient.uploadWithProgress(audioFile, DEFAULT_SERVER_URL, "th")
                    .collect { progress ->
                        _networkProgress.value = progress
                        
                        when (progress) {
                            is NetworkProgress.Success -> {
                                Log.d(TAG, "Transcription successful")
                                _transcriptionResult.value = progress.result
                                _networkProgress.value = null
                            }
                            is NetworkProgress.Error -> {
                                Log.e(TAG, "Transcription failed", progress.exception)
                                handleNetworkError(progress.exception)
                            }
                            else -> {
                                // Progress updates
                                Log.d(TAG, "Transcription progress: $progress")
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error during transcription process", e)
                handleNetworkError(e)
            }
        }
    }

    /**
     * Handle recording errors
     */
    private fun handleRecordingError(exception: Throwable) {
        _networkProgress.value = NetworkProgress.Error(
            Exception("Recording failed: ${exception.message}", exception)
        )
    }

    /**
     * Handle network errors
     */
    private fun handleNetworkError(exception: Exception) {
        _networkProgress.value = NetworkProgress.Error(exception)
    }

    /**
     * Permission granted by user
     */
    fun onPermissionGranted() {
        Log.d(TAG, "Microphone permission granted")
        _hasPermission.value = true
        _showPermissionDialog.value = false
    }

    /**
     * Permission denied by user
     */
    fun onPermissionDenied() {
        Log.d(TAG, "Microphone permission denied")
        _hasPermission.value = false
        _showPermissionDialog.value = true
    }

    /**
     * Dismiss permission dialog
     */
    fun dismissPermissionDialog() {
        _showPermissionDialog.value = false
    }

    /**
     * Update wearable connection status
     */
    fun setWearableConnectionStatus(isConnected: Boolean) {
        _isWearableConnected.value = isConnected
    }

    /**
     * Clear transcription result
     */
    fun clearTranscriptionResult() {
        _transcriptionResult.value = null
        _networkProgress.value = null
    }

    /**
     * Test server connection
     */
    fun testServerConnection(serverUrl: String = DEFAULT_SERVER_URL) {
        viewModelScope.launch {
            try {
                val result = networkClient.testConnection(serverUrl)
                result.fold(
                    onSuccess = { isConnected ->
                        Log.d(TAG, "Server connection test: $isConnected")
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Server connection test failed", exception)
                        handleNetworkError(Exception("Server connection failed", exception))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error testing server connection", e)
                handleNetworkError(e)
            }
        }
    }

    /**
     * Cancel current recording
     */
    fun cancelRecording() {
        Log.d(TAG, "Cancelling recording")
        audioRecorder.cancelRecording()
        _transcriptionResult.value = null
        _networkProgress.value = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared, cleaning up resources")
        audioRecorder.cancelRecording()
        networkClient.cleanup()
    }
}

/**
 * UI State data class for the main screen
 */
data class AudioCaptureUiState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val audioLevel: Float = 0f,
    val networkProgress: NetworkProgress? = null,
    val transcriptionResult: TranscriptionResponse? = null,
    val isWearableConnected: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val isRecordButtonEnabled: Boolean = false,
    val errorMessage: String? = null
)