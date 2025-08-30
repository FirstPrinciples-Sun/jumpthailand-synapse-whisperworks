package com.yourdomain.whisperworks

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for WhisperWorks
 * Handles global app initialization and configuration
 */
@HiltAndroidApp
class WhisperWorksApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "WhisperWorksApp"

        // Notification Channels
        const val CHANNEL_RECORDING = "recording_channel"
        const val CHANNEL_WEARABLE = "wearable_channel"
        const val CHANNEL_GENERAL = "general_channel"

        lateinit var instance: WhisperWorksApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize logging
        initializeLogging()

        // Create notification channels
        createNotificationChannels()

        // Initialize crash reporting (if needed)
        // initializeCrashlytics()

        Timber.i("WhisperWorks Application initialized successfully")
    }

    /**
     * Initialize logging with Timber
     */
    private fun initializeLogging() {
        if (BuildConfig.DEBUG) {
            // Debug logging with line numbers and method names
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String {
                    return "🎤 ${super.createStackElementTag(element)}:${element.lineNumber}"
                }
            })
        } else {
            // Production logging (can add crash reporting here)
            Timber.plant(CrashReportingTree())
        }
    }

    /**
     * Create notification channels for different types of notifications
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Recording Channel
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                "Recording Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for audio recording status"
                enableVibration(true)
                setShowBadge(true)
            }

            // Wearable Channel
            val wearableChannel = NotificationChannel(
                CHANNEL_WEARABLE,
                "Wearable Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for wearable device connection status"
                enableVibration(false)
                setShowBadge(false)
            }

            // General Channel
            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
                enableVibration(true)
                setShowBadge(true)
            }

            // Register channels
            notificationManager.createNotificationChannels(
                listOf(recordingChannel, wearableChannel, generalChannel)
            )

            Timber.d("Notification channels created successfully")
        }
    }

    /**
     * Provide WorkManager configuration
     */
    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO)
            .build()
    }

    /**
     * Get application context safely
     */
    fun getAppContext(): Context = applicationContext
}

/**
 * Custom Timber tree for production crash reporting
 */
class CrashReportingTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.VERBOSE || priority == Log.DEBUG) {
            return
        }

        // Log to system
        Log.println(priority, tag, message)

        // Report crashes to crash reporting service
        if (priority == Log.ERROR && t != null) {
            // Add crash reporting here (e.g., Crashlytics)
            // FirebaseCrashlytics.getInstance().recordException(t)
        }
    }
}
