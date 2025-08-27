package com.yourdomain.whisperworks

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    val outputFile: File by lazy {
        File(context.cacheDir, "recorded_audio.pcm")
    }

    val isReady: Boolean
        get() = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0

    fun startRecording() {
        if (isRecording) return

        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000, // Sample rate required by Vosk
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
            return
        }

        isRecording = true
        audioRecord?.startRecording()
    }

    suspend fun stopRecording(): File? {
        if (!isRecording) return null
        
        // Stop recording immediately
        val localAudioRecord = audioRecord
        localAudioRecord?.stop()
        isRecording = false

        // Read the remaining buffer and write to file
        val data = ByteArray(localAudioRecord?.bufferSizeInFrames ?: 0)
        withContext(Dispatchers.IO) {
            val fileOutputStream = FileOutputStream(outputFile)
            while (localAudioRecord?.read(data, 0, data.size) != AudioRecord.ERROR_INVALID_OPERATION) {
                fileOutputStream.write(data)
            }
            fileOutputStream.close()
        }
        localAudioRecord?.release()
        audioRecord = null
        
        return outputFile
    }
}