package com.yourdomain.whisperworks.domain.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced AudioRecorder with better error handling, memory management, and audio quality
 */
@Singleton
class AudioRecorder @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000 // Required by Vosk
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

        // Audio quality settings
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    // State management
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // File management
    val outputFile: File by lazy {
        File(context.cacheDir, "recorded_audio_${System.currentTimeMillis()}.pcm")
    }

    private val bufferSize: Int by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        maxOf(minBufferSize, minBufferSize * BUFFER_SIZE_MULTIPLIER)
    }

    val isReady: Boolean
        get() {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            return minBufferSize != AudioRecord.ERROR_BAD_VALUE && minBufferSize != AudioRecord.ERROR
        }

    /**
     * Start recording audio with enhanced error handling
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Result<Unit> {
        if (isRecording) {
            return Result.failure(IllegalStateException("Already recording"))
        }

        if (!isReady) {
            return Result.failure(IllegalStateException("AudioRecord is not ready"))
        }

        return try {
            // Create AudioRecord instance
            audioRecord = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).apply {
                // Verify the AudioRecord was created successfully
                if (state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed")
                }
            }

            // Start recording
            audioRecord?.startRecording()
            isRecording = true
            _recordingState.value = RecordingState.RECORDING

            // Start recording thread
            startRecordingThread()

            Log.d(TAG, "Recording started successfully")
            Result.success(Unit)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
            cleanup()
            Result.failure(SecurityException("Microphone permission required", e))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Stop recording and save audio to file
     */
    suspend fun stopRecording(): Result<File> = withContext(Dispatchers.IO) {
        if (!isRecording) {
            return@withContext Result.failure(IllegalStateException("Not currently recording"))
        }

        return@withContext try {
            _recordingState.value = RecordingState.STOPPING

            // Stop recording
            isRecording = false
            audioRecord?.stop()

            // Wait for recording thread to finish
            recordingThread?.join(5000) // Wait max 5 seconds

            // Verify file was created and has content
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw IOException("Recording file is empty or doesn't exist")
            }

            _recordingState.value = RecordingState.COMPLETED
            Log.d(TAG, "Recording stopped. File size: ${outputFile.length()} bytes")

            Result.success(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            _recordingState.value = RecordingState.ERROR
            Result.failure(e)
        } finally {
            cleanup()
        }
    }

    /**
     * Cancel current recording without saving
     */
    fun cancelRecording() {
        if (!isRecording) return

        try {
            isRecording = false
            audioRecord?.stop()
            recordingThread?.interrupt()

            // Delete the file if it exists
            if (outputFile.exists()) {
                outputFile.delete()
            }

            _recordingState.value = RecordingState.CANCELLED
            Log.d(TAG, "Recording cancelled")

        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling recording", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Start the recording thread
     */
    private fun startRecordingThread() {
        recordingThread = Thread({
            try {
                recordAudioData()
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording thread", e)
                _recordingState.value = RecordingState.ERROR
            }
        }, "AudioRecordingThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Record audio data to file with real-time audio level monitoring
     */
    private fun recordAudioData() {
        val audioData = ByteArray(bufferSize)
        var fileOutputStream: FileOutputStream? = null

        try {
            fileOutputStream = FileOutputStream(outputFile)
            Log.d(TAG, "Started writing to file: ${outputFile.absolutePath}")

            while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(audioData, 0, audioData.size) ?: 0

                if (bytesRead > 0) {
                    // Write to file
                    fileOutputStream.write(audioData, 0, bytesRead)

                    // Calculate audio level for UI feedback
                    val audioLevel = calculateAudioLevel(audioData, bytesRead)
                    _audioLevel.value = audioLevel

                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.w(TAG, "AudioRecord read error: INVALID_OPERATION")
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "AudioRecord read error: BAD_VALUE")
                    break
                }
            }

        } catch (e: IOException) {
            Log.e(TAG, "Error writing audio data", e)
            _recordingState.value = RecordingState.ERROR
        } finally {
            try {
                fileOutputStream?.flush()
                fileOutputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing file output stream", e)
            }
        }
    }

    /**
     * Calculate audio level for real-time feedback
     */
    private fun calculateAudioLevel(audioData: ByteArray, bytesRead: Int): Float {
        var sum = 0L
        for (i in 0 until bytesRead step 2) {
            val sample = ((audioData[i + 1].toInt() shl 8) or (audioData[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toLong()
        }
        val rms = Math.sqrt(sum.toDouble() / (bytesRead / 2)).toFloat()
        return (rms / Short.MAX_VALUE).coerceIn(0f, 1f)
    }

    /**
     * Clean up resources
     */
    private fun cleanup() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
            recordingThread = null
            _audioLevel.value = 0f
            if (_recordingState.value != RecordingState.ERROR) {
                _recordingState.value = RecordingState.IDLE
            }
        }
    }

    /**
     * Get recording duration in milliseconds
     */
    fun getRecordingDuration(): Long {
        return if (outputFile.exists()) {
            // Calculate duration based on file size and audio format
            val fileSizeBytes = outputFile.length()
            val bytesPerSecond = SAMPLE_RATE * 2 // 16-bit mono
            (fileSizeBytes * 1000 / bytesPerSecond)
        } else {
            0L
        }
    }
}

/**
 * Recording states for better state management
 */
enum class RecordingState {
    IDLE,
    RECORDING,
    STOPPING,
    COMPLETED,
    CANCELLED,
    ERROR
}
