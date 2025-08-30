package com.yourdomain.whisperworks.wear.domain.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Enhanced AudioTrigger for WearOS with improved performance and error handling
 */
@Singleton
class AudioTrigger @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioTrigger"
        private const val SAMPLE_RATE = 8000 // Lower sample rate for watch to save battery
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Trigger thresholds
        private const val AMPLITUDE_THRESHOLD = 1500f
        private const val COOLDOWN_DURATION_MS = 5000L
        private const val VIBRATION_DURATION_MS = 100L

        // Buffer settings
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }

    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State management
    private val _listeningState = MutableStateFlow(AudioTriggerState.STOPPED)
    val listeningState: StateFlow<AudioTriggerState> = _listeningState.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _triggerCount = MutableStateFlow(0)
    val triggerCount: StateFlow<Int> = _triggerCount.asStateFlow()

    // Configuration
    private var amplitudeThreshold = AMPLITUDE_THRESHOLD
    private var isInCooldown = false

    // Audio configuration
    private val bufferSize: Int by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        maxOf(minBufferSize, minBufferSize * BUFFER_SIZE_MULTIPLIER)
    }

    // Vibrator for haptic feedback
    private val vibrator: Vibrator? by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Check if AudioTrigger is ready to use
     */
    val isReady: Boolean
        get() {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            return minBufferSize != AudioRecord.ERROR_BAD_VALUE && minBufferSize != AudioRecord.ERROR
        }

    /**
     * Start listening for audio triggers
     */
    @SuppressLint("MissingPermission")
    fun startListening(
        onTrigger: () -> Unit,
        customThreshold: Float? = null
    ): Result<Unit> {
        if (_listeningState.value == AudioTriggerState.LISTENING) {
            return Result.failure(IllegalStateException("Already listening"))
        }

        if (!isReady) {
            return Result.failure(IllegalStateException("AudioTrigger is not ready"))
        }

        customThreshold?.let { amplitudeThreshold = it }

        return try {
            // Initialize AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).apply {
                if (state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed")
                }
            }

            // Start recording
            audioRecord?.startRecording()
            _listeningState.value = AudioTriggerState.LISTENING

            // Start listening job
            startListeningJob(onTrigger)

            Timber.d("Audio trigger started successfully")
            Result.success(Unit)

        } catch (e: SecurityException) {
            Timber.e(e, "Permission denied for audio recording")
            cleanup()
            Result.failure(SecurityException("Microphone permission required", e))
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio trigger")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Stop listening for audio triggers
     */
    fun stopListening() {
        if (_listeningState.value == AudioTriggerState.STOPPED) return

        Timber.d("Stopping audio trigger")
        _listeningState.value = AudioTriggerState.STOPPING

        // Cancel listening job
        listeningJob?.cancel()
        listeningJob = null

        // Cleanup audio resources
        cleanup()

        _listeningState.value = AudioTriggerState.STOPPED
        _audioLevel.value = 0f
        Timber.d("Audio trigger stopped")
    }

    /**
     * Update amplitude threshold
     */
    fun updateThreshold(threshold: Float) {
        amplitudeThreshold = threshold.coerceIn(500f, 5000f)
        Timber.d("Amplitude threshold updated to $amplitudeThreshold")
    }

    /**
     * Get current configuration
     */
    fun getConfiguration(): AudioTriggerConfig {
        return AudioTriggerConfig(
            sampleRate = SAMPLE_RATE,
            amplitudeThreshold = amplitudeThreshold,
            cooldownDuration = COOLDOWN_DURATION_MS,
            bufferSize = bufferSize
        )
    }

    /**
     * Start the audio listening job
     */
    private fun startListeningJob(onTrigger: () -> Unit) {
        listeningJob = scope.launch {
            val buffer = ShortArray(bufferSize / 2) // 16-bit samples

            try {
                while (isActive && _listeningState.value == AudioTriggerState.LISTENING) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (readSize > 0) {
                        processAudioBuffer(buffer, readSize, onTrigger)
                    } else {
                        // Handle read errors
                        when (readSize) {
                            AudioRecord.ERROR_INVALID_OPERATION -> {
                                Timber.w("AudioRecord read error: INVALID_OPERATION")
                                break
                            }
                            AudioRecord.ERROR_BAD_VALUE -> {
                                Timber.w("AudioRecord read error: BAD_VALUE")
                                break
                            }
                            AudioRecord.ERROR_DEAD_OBJECT -> {
                                Timber.w("AudioRecord read error: DEAD_OBJECT")
                                break
                            }
                        }
                    }

                    // Small delay to prevent excessive CPU usage
                    delay(10)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in audio listening loop")
                _listeningState.value = AudioTriggerState.ERROR
            }
        }
    }

    /**
     * Process audio buffer and check for trigger conditions
     */
    private suspend fun processAudioBuffer(
        buffer: ShortArray,
        readSize: Int,
        onTrigger: () -> Unit
    ) {
        // Calculate RMS (Root Mean Square) amplitude
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        val rms = sqrt(sum / readSize).toFloat()

        // Update audio level for UI
        val normalizedLevel = (rms / amplitudeThreshold).coerceIn(0f, 1f)
        _audioLevel.value = normalizedLevel

        // Check for trigger condition
        if (rms > amplitudeThreshold && !isInCooldown) {
            handleTrigger(onTrigger)
        }
    }

    /**
     * Handle trigger event with cooldown
     */
    private fun handleTrigger(onTrigger: () -> Unit) {
        if (isInCooldown) return

        Timber.d("Audio trigger detected! RMS above threshold")

        // Increment trigger count
        _triggerCount.value += 1

        // Provide haptic feedback
        provideHapticFeedback()

        // Execute trigger callback
        scope.launch(Dispatchers.Main) {
            try {
                onTrigger()
            } catch (e: Exception) {
                Timber.e(e, "Error executing trigger callback")
            }
        }

        // Start cooldown period
        startCooldown()
    }

    /**
     * Provide haptic feedback on trigger
     */
    private fun provideHapticFeedback() {
        try {
            vibrator?.let { v ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(
                        VibrationEffect.createOneShot(
                            VIBRATION_DURATION_MS,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(VIBRATION_DURATION_MS)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to provide haptic feedback")
        }
    }

    /**
     * Start cooldown period to prevent multiple triggers
     */
    private fun startCooldown() {
        scope.launch {
            isInCooldown = true
            delay(COOLDOWN_DURATION_MS)
            isInCooldown = false
            Timber.d("Cooldown period ended")
        }
    }

    /**
     * Clean up audio resources
     */
    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up AudioRecord")
        } finally {
            audioRecord = null
        }
    }

    /**
     * Clean up when object is destroyed
     */
    fun destroy() {
        stopListening()
        scope.cancel()
        Timber.d("AudioTrigger destroyed")
    }
}

/**
 * Audio trigger states
 */
enum class AudioTriggerState {
    STOPPED,
    LISTENING,
    STOPPING,
    ERROR
}

/**
 * Audio trigger configuration
 */
data class AudioTriggerConfig(
    val sampleRate: Int,
    val amplitudeThreshold: Float,
    val cooldownDuration: Long,
    val bufferSize: Int
)
