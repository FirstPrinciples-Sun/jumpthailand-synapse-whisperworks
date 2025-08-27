package com.yourdomain.whisperworks

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class TranscriptionResponse(
    val full_text: String,
    val keywords: List<String>
)

object NetworkClient {
    // IMPORTANT: Replace with your actual AIS Edge Compute Server IP address
    private const val API_URL = "http://YOUR_SERVER_IP:8000/transcribe"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun uploadAndTranscribe(file: File): Result<TranscriptionResponse> {
        return try {
            Log.d("NetworkClient", "Uploading file: ${file.name} to $API_URL")

            val response = client.post(API_URL) {
                setBody(MultiPartFormDataContent(
                    formData {
                        append("audio_file", file.readBytes(), Headers.build {
                            append(HttpHeaders.ContentType, "audio/pcm")
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        })
                    }
                ))
            }

            if (response.status.isSuccess()) {
                val transcriptionResponse = response.body<TranscriptionResponse>()
                Log.d("NetworkClient", "Success: ${transcriptionResponse.transcription}")
                Result.success(transcriptionResponse)
            } else {
                Log.e("NetworkClient", "Error: ${response.status}")
                Result.failure(Exception("Server returned error: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("NetworkClient", "Exception: ${e.message}", e)
            Result.failure(e)
        }
    }
}