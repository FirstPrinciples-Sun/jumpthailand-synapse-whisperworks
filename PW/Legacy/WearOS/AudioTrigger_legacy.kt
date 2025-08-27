package com.yourdomain.whisperworks

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@SuppressLint("MissingPermission")
class AudioTrigger(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 8000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var isListening = false

    private val AMPLITUDE_THRESHOLD = 1500

    fun startListening(onTrigger: () -> Unit) {
        if (isListening || bufferSize == AudioRecord.ERROR_BAD_VALUE) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        isListening = true
        audioRecord?.startRecording()

        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(bufferSize)
            while (this.isActive && isListening) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    var sum = 0.0
                    for (i in 0 until readSize) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = sqrt(sum / readSize)

                    if (rms > AMPLITUDE_THRESHOLD) {
                        onTrigger()
                        delay(5000) // Cooldown period
                    }
                }
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}