package com.yourdomain.whisperworks.data.network

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced Network Client with better error handling, retry mechanism, and progress tracking
 */
@Singleton
class NetworkClient @Inject constructor() {

    companion object {
        private const val TAG = "NetworkClient"

        // Configuration
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L

        // Default server URL - should be replaced with actual server
        private const val DEFAULT_SERVER_URL = "https://your-server.com"
    }

    // JSON configuration
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    // HTTP Client with enhanced configuration
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }

        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_TIMEOUT_MS
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = DEFAULT_TIMEOUT_MS
        }

        install(DefaultRequest) {
            header(HttpHeaders.UserAgent, "WhisperWorks-Android/1.0")
        }

        // Retry mechanism
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = MAX_RETRY_ATTEMPTS)
            retryOnException(maxRetries = MAX_RETRY_ATTEMPTS)
            delayMillis { retry -> retry * RETRY_DELAY_MS }
        }

        engine {
            // CIO engine configuration
            maxConnectionsCount = 10
            endpoint {
                maxConnectionsPerRoute = 5
                connectTimeout = 30_000L
                requestTimeout = DEFAULT_TIMEOUT_MS
            }
        }
    }

    /**
     * Upload audio file and get transcription with progress tracking
     */
    suspend fun uploadAndTranscribe(
        file: File,
        serverUrl: String = DEFAULT_SERVER_URL,
        language: String = "th"
    ): Result<TranscriptionResponse> {
        return try {
            validateFile(file)

            val apiUrl = "$serverUrl/transcribe"
            Log.d(TAG, "Uploading file: ${file.name} (${file.length()} bytes) to $apiUrl")

            val response = httpClient.post(apiUrl) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("audio_file", file.readBytes(), Headers.build {
                                append(HttpHeaders.ContentType, "audio/pcm")
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            })
                            append("language", language)
                            append("model", "vosk-thai")
                        }
                    )
                )

                // Progress callback could be added here in the future
                onUpload { bytesSentTotal, contentLength ->
                    val progress = (bytesSentTotal.toFloat() / contentLength.toFloat() * 100).toInt()
                    Log.d(TAG, "Upload progress: $progress%")
                }
            }

            handleResponse(response)

        } catch (e: Exception) {
            Log.e(TAG, "Upload and transcribe failed", e)
            Result.failure(mapException(e))
        }
    }

    /**
     * Upload audio file with progress tracking
     */
    fun uploadWithProgress(
        file: File,
        serverUrl: String = DEFAULT_SERVER_URL,
        language: String = "th"
    ): Flow<NetworkProgress> = flow {
        try {
            validateFile(file)
            emit(NetworkProgress.Started)

            val apiUrl = "$serverUrl/transcribe"

            val response = httpClient.post(apiUrl) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("audio_file", file.readBytes(), Headers.build {
                                append(HttpHeaders.ContentType, "audio/pcm")
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            })
                            append("language", language)
                        }
                    )
                )

                onUpload { bytesSentTotal, contentLength ->
                    val progress = (bytesSentTotal.toFloat() / contentLength.toFloat() * 100).toInt()
                    emit(NetworkProgress.Uploading(progress))
                }
            }

            emit(NetworkProgress.Processing)

            val result = handleResponse(response)
            result.fold(
                onSuccess = { emit(NetworkProgress.Success(it)) },
                onFailure = { emit(NetworkProgress.Error(mapException(it))) }
            )

        } catch (e: Exception) {
            emit(NetworkProgress.Error(mapException(e)))
        }
    }

    /**
     * Get server health status
     */
    suspend fun getServerStatus(serverUrl: String = DEFAULT_SERVER_URL): Result<ServerStatus> {
        return try {
            val response = httpClient.get("$serverUrl/health")

            if (response.status.isSuccess()) {
                val status = response.body<ServerStatus>()
                Result.success(status)
            } else {
                Result.failure(HttpException("Server health check failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server status check failed", e)
            Result.failure(mapException(e))
        }
    }

    /**
     * Test server connectivity
     */
    suspend fun testConnection(serverUrl: String): Result<Boolean> {
        return try {
            val response = httpClient.get("$serverUrl/ping") {
                timeout {
                    requestTimeoutMillis = 10_000L
                }
            }
            Result.success(response.status.isSuccess())
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed for $serverUrl", e)
            Result.failure(mapException(e))
        }
    }

    /**
     * Validate audio file before upload
     */
    private fun validateFile(file: File) {
        when {
            !file.exists() -> throw IllegalArgumentException("Audio file does not exist")
            file.length() == 0L -> throw IllegalArgumentException("Audio file is empty")
            file.length() > 50 * 1024 * 1024 -> throw IllegalArgumentException("Audio file too large (max 50MB)")
            !file.canRead() -> throw IllegalArgumentException("Cannot read audio file")
        }
    }

    /**
     * Handle HTTP response and parse transcription result
     */
    private suspend fun handleResponse(response: HttpResponse): Result<TranscriptionResponse> {
        return try {
            when {
                response.status.isSuccess() -> {
                    val transcriptionResponse = response.body<TranscriptionResponse>()
                    Log.d(TAG, "Transcription successful: ${transcriptionResponse.full_text}")
                    Result.success(transcriptionResponse)
                }
                response.status == HttpStatusCode.BadRequest -> {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "Bad request: $errorBody")
                    Result.failure(BadRequestException(errorBody))
                }
                response.status == HttpStatusCode.InternalServerError -> {
                    Log.e(TAG, "Server error: ${response.status}")
                    Result.failure(ServerErrorException("Internal server error"))
                }
                else -> {
                    Log.e(TAG, "HTTP error: ${response.status}")
                    Result.failure(HttpException("HTTP ${response.status.value}: ${response.status.description}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
            Result.failure(ResponseParsingException("Failed to parse server response", e))
        }
    }

    /**
     * Map exceptions to more specific types
     */
    private fun mapException(throwable: Throwable): Exception {
        return when (throwable) {
            is HttpRequestTimeoutException -> NetworkTimeoutException("Request timed out", throwable)
            is ConnectTimeoutException -> NetworkTimeoutException("Connection timed out", throwable)
            is ResponseException -> HttpException("HTTP ${throwable.response.status.value}", throwable)
            is Exception -> throwable
            else -> Exception("Unknown network error", throwable)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        try {
            httpClient.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HTTP client", e)
        }
    }
}

/**
 * Data models
 */
@Serializable
data class TranscriptionResponse(
    val full_text: String,
    val keywords: List<String>,
    val confidence: Float? = null,
    val processing_time: Double? = null,
    val language: String? = null
)

@Serializable
data class ServerStatus(
    val status: String,
    val version: String? = null,
    val model_loaded: Boolean = false,
    val supported_languages: List<String> = emptyList()
)

/**
 * Progress tracking for uploads
 */
sealed class NetworkProgress {
    object Started : NetworkProgress()
    data class Uploading(val progress: Int) : NetworkProgress()
    object Processing : NetworkProgress()
    data class Success(val result: TranscriptionResponse) : NetworkProgress()
    data class Error(val exception: Exception) : NetworkProgress()
}

/**
 * Custom exceptions for better error handling
 */
class NetworkTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)
class HttpException(message: String, cause: Throwable? = null) : Exception(message, cause)
class BadRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ServerErrorException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ResponseParsingException(message: String, cause: Throwable? = null) : Exception(message, cause)
