package com.yourdomain.whisperworks.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourdomain.whisperworks.wear.data.repository.WearableRepository
import com.yourdomain.whisperworks.wear.domain.audio.AudioTrigger
import com.yourdomain.whisperworks.wear.domain.audio.AudioTriggerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for WearOS main screen
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val audioTrigger: AudioTrigger,
    private val wearableRepository: WearableRepository
) : ViewModel() {

    companion object {
        private const val TAG = "WearMainViewModel"
        private const val CUSTOM_AUDIO_THRESHOLD = 2000f
    }

    // Internal state
    private val _uiState = MutableStateFlow(WearUiState())
    val uiState: StateFlow<WearUiState> = _uiState.asStateFlow()
    
    private val _hasPermission = MutableStateFlow(false)
    private val _showPermissionDialog = MutableStateFlow(false)

    init {
        // Initialize state observation
        observeAudioTriggerState()
        observeWearableConnection()
        observeAudioLevel()
        
        Timber.d("WearOS MainViewModel initialized")
    }

    /**
     * Called when activity resumes
     */
    fun onResume() {
        Timber.d("ViewModel resumed")
        wearableRepository.checkConnectionStatus()
    }

    /**
     * Called when activity pauses
     */
    fun onPause() {
        Timber.d("ViewModel paused")
        // Stop listening to save battery
        if (_uiState.value.triggerState == AudioTriggerState.LISTENING) {
            stopListening()
        }
    }

    /**
     * Start listening for audio triggers
     */
    fun startListening() {
        if (!_hasPermission.value) {
            _showPermissionDialog.value = true
            return
        }

        Timber.d("Starting audio listening")
        
        viewModelScope.launch {
            val result = audioTrigger.startListening(
                onTrigger = { handleAudioTrigger() },
                customThreshold = CUSTOM_AUDIO_THRESHOLD
            )
            
            result.onFailure { exception ->
                Timber.e(exception, "Failed to start listening")
                updateErrorState("Failed to start listening: ${exception.message}")
            }
        }
    }

    /**
     * Stop listening for audio triggers
     */
    fun stopListening() {
        Timber.d("Stopping audio listening")
        audioTrigger.stopListening()
    }

    /**
     * Send manual trigger to phone
     */
    fun sendManualTrigger() {
        Timber.d("Sending manual trigger")
        
        viewModelScope.launch {
            val result = wearableRepository.sendTriggerToPhone("manual_trigger")
            
            result.fold(
                onSuccess = { 
                    Timber.d("Manual trigger sent successfully")
                    updateTriggerSentState()
                },
                onFailure = { exception ->
                    Timber.e(exception, "Failed to send manual trigger")
                    updateErrorState("Failed to send trigger: ${exception.message}")
                }
            )
        }
    }

    /**
     * Handle audio trigger detection
     */
    private fun handleAudioTrigger() {
        Timber.d("Audio trigger detected")
        
        viewModelScope.launch {
            try {
                // Send trigger to phone
                val result = wearableRepository.sendTriggerToPhone("voice_trigger")
                
                result.fold(
                    onSuccess = {
                        Timber.d("Voice trigger sent successfully")
                        updateTriggerSentState()
                    },
                    onFailure = { exception ->
                        Timber.e(exception, "Failed to send voice trigger")
                        updateErrorState("Failed to send trigger")
                    }
                )
                
            } catch (e: Exception) {
                Timber.e(e, "Error handling audio trigger")
                updateErrorState("Trigger handling error")
            }
        }
    }

    /**
     * Permission granted by user
     */
    fun onPermissionGranted() {
        Timber.d("Microphone permission granted")
        _hasPermission.value = true
        _showPermissionDialog.value = false
        
        updateUiState { it.copy(hasPermission = true, showPermissionDialog = false) }
    }

    /**
     * Permission denied by user
     */
    fun onPermissionDenied() {
        Timber.d("Microphone permission denied")
        _hasPermission.value = false
        _showPermissionDialog.value = true
        
        updateUiState { it.copy(hasPermission = false, showPermissionDialog = true) }
    }

    /**
     * Dismiss permission dialog
     */
    fun dismissPermissionDialog() {
        _showPermissionDialog.value = false
        updateUiState { it.copy(showPermissionDialog = false) }
    }

    /**
     * Observe audio trigger state changes
     */
    private fun observeAudioTriggerState() {
        viewModelScope.launch {
            audioTrigger.listeningState.collect { state ->
                updateUiState { it.copy(triggerState = state) }
            }
        }
    }

    /**
     * Observe wearable connection changes
     */
    private fun observeWearableConnection() {
        viewModelScope.launch {
            wearableRepository.connectionStatus.collect { status ->
                updateUiState { it.copy(connectionStatus = status) }
            }
        }
        
        viewModelScope.launch {
            wearableRepository.connectedDevices.collect { devices ->
                updateUiState { 
                    it.copy(connectedDevices = devices.map { device -> device.displayName })
                }
            }
        }
    }

    /**
     * Observe audio level changes
     */
    private fun observeAudioLevel() {
        viewModelScope.launch {
            audioTrigger.audioLevel.collect { level ->
                updateUiState { it.copy(audioLevel = level) }
            }
        }
    }

    /**
     * Update UI state helper
     */
    private fun updateUiState(update: (WearUiState) -> WearUiState) {
        _uiState.value = update(_uiState.value)
    }

    /**
     * Update state when trigger is sent
     */
    private fun updateTriggerSentState() {
        updateUiState { 
            it.copy(
                lastTriggerTime = System.currentTimeMillis(),
                errorMessage = null
            ) 
        }
    }

    /**
     * Update error state
     */
    private fun updateErrorState(message: String) {
        updateUiState { 
            it.copy(
                errorMessage = message,
                triggerState = AudioTriggerState.ERROR
            ) 
        }
    }

    /**
     * Get trigger statistics
     */
    fun getTriggerStats(): TriggerStats {
        return TriggerStats(
            totalTriggers = audioTrigger.triggerCount.value,
            listeningDuration = calculateListeningDuration(),
            lastTriggerTime = _uiState.value.lastTriggerTime
        )
    }

    /**
     * Reset trigger count
     */
    fun resetTriggerCount() {
        // This would require adding a reset method to AudioTrigger
        Timber.d("Reset trigger count requested")
    }

    /**
     * Calculate listening duration (placeholder)
     */
    private fun calculateListeningDuration(): Long {
        // Implement duration calculation logic
        return 0L
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("ViewModel cleared, stopping audio trigger")
        audioTrigger.destroy()
    }
}

/**
 * UI State for WearOS main screen
 */
data class WearUiState(
    val triggerState: AudioTriggerState = AudioTriggerState.STOPPED,
    val connectionStatus: WearableConnectionStatus = WearableConnectionStatus.DISCONNECTED,
    val connectedDevices: List<String> = emptyList(),
    val audioLevel: Float = 0f,
    val hasPermission: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val lastTriggerTime: Long? = null,
    val errorMessage: String? = null
)

/**
 * Trigger statistics
 */
data class TriggerStats(
    val totalTriggers: Int,
    val listeningDuration: Long,
    val lastTriggerTime: Long?
)