package com.yourdomain.whisperworks.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.wearable.CapabilityEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.yourdomain.whisperworks.R
import com.yourdomain.whisperworks.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Enhanced Wearable Listener Service with better message handling and error recovery
 */
class DataLayerListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "DataLayerListener"

        // Message paths
        private const val TRIGGER_RECORDING_PATH = "/whisper-works-trigger"
        private const val STOP_RECORDING_PATH = "/whisper-works-stop"
        private const val STATUS_REQUEST_PATH = "/whisper-works-status"

        // Data paths
        private const val CONFIG_PATH = "/whisper-works-config"

        // Broadcast actions
        const val ACTION_START_RECORDING = "com.yourdomain.whisperworks.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.yourdomain.whisperworks.STOP_RECORDING"
        const val ACTION_STATUS_REQUEST = "com.yourdomain.whisperworks.STATUS_REQUEST"
        const val ACTION_CONFIG_UPDATED = "com.yourdomain.whisperworks.CONFIG_UPDATED"

        // Extras
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_TIMESTAMP = "timestamp"

        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "whisper_works_wearable"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DataLayerListenerService created")
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DataLayerListenerService destroyed")
    }

    /**
     * Handle messages from Wearable devices
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        Log.d(TAG, "Message received from ${messageEvent.sourceNodeId}: ${messageEvent.path}")

        serviceScope.launch {
            try {
                when (messageEvent.path) {
                    TRIGGER_RECORDING_PATH -> handleTriggerRecording(messageEvent)
                    STOP_RECORDING_PATH -> handleStopRecording(messageEvent)
                    STATUS_REQUEST_PATH -> handleStatusRequest(messageEvent)
                    else -> {
                        Log.w(TAG, "Unknown message path: ${messageEvent.path}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling message: ${messageEvent.path}", e)
            }
        }
    }

    /**
     * Handle data changes from Wearable devices
     */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)

        serviceScope.launch {
            try {
                dataEvents.forEach { dataEvent ->
                    when (dataEvent.type) {
                        DataEvent.TYPE_CHANGED -> handleDataChanged(dataEvent)
                        DataEvent.TYPE_DELETED -> handleDataDeleted(dataEvent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling data changes", e)
            } finally {
                dataEvents.release()
            }
        }
    }

    /**
     * Handle capability changes (when wearable connects/disconnects)
     */
    override fun onCapabilityChanged(capabilityEvent: CapabilityEvent) {
        super.onCapabilityChanged(capabilityEvent)

        Log.d(TAG, "Capability changed: ${capabilityEvent.capability}")
        Log.d(TAG, "Nodes: ${capabilityEvent.nodes.joinToString { it.displayName }}")

        // Show notification when wearable connects/disconnects
        val connectedNodes = capabilityEvent.nodes.filter { it.isNearby }
        if (connectedNodes.isNotEmpty()) {
            showConnectionNotification("Wearable connected: ${connectedNodes.first().displayName}")
        }
    }

    /**
     * Handle recording trigger from wearable
     */
    private fun handleTriggerRecording(messageEvent: MessageEvent) {
        val messageData = try {
            val messageString = String(messageEvent.data)
            if (messageString.isNotEmpty()) {
                json.decodeFromString<WearableMessage>(messageString)
            } else {
                WearableMessage(action = "trigger_recording")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message data, using default", e)
            WearableMessage(action = "trigger_recording")
        }

        Log.d(TAG, "Recording triggered from wearable: $messageData")

        // Send broadcast to MainActivity
        val intent = Intent(ACTION_START_RECORDING).apply {
            putExtra(EXTRA_MESSAGE, messageData.action)
            putExtra(EXTRA_NODE_ID, messageEvent.sourceNodeId)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // Show notification
        showActionNotification("Recording started from wearable")
    }

    /**
     * Handle stop recording from wearable
     */
    private fun handleStopRecording(messageEvent: MessageEvent) {
        Log.d(TAG, "Stop recording triggered from wearable")

        val intent = Intent(ACTION_STOP_RECORDING).apply {
            putExtra(EXTRA_NODE_ID, messageEvent.sourceNodeId)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        showActionNotification("Recording stopped from wearable")
    }

    /**
     * Handle status request from wearable
     */
    private fun handleStatusRequest(messageEvent: MessageEvent) {
        Log.d(TAG, "Status request from wearable")

        val intent = Intent(ACTION_STATUS_REQUEST).apply {
            putExtra(EXTRA_NODE_ID, messageEvent.sourceNodeId)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Handle data changes (configuration updates)
     */
    private fun handleDataChanged(dataEvent: DataEvent) {
        when (dataEvent.dataItem.uri.path) {
            CONFIG_PATH -> {
                val dataMap = DataMapItem.fromDataItem(dataEvent.dataItem).dataMap
                val config = parseConfigFromDataMap(dataMap)

                Log.d(TAG, "Configuration updated: $config")

                val intent = Intent(ACTION_CONFIG_UPDATED).apply {
                    putExtra(EXTRA_CONFIG, config.toString())
                    putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                }

                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
        }
    }

    /**
     * Handle data deletion
     */
    private fun handleDataDeleted(dataEvent: DataEvent) {
        Log.d(TAG, "Data deleted: ${dataEvent.dataItem.uri.path}")
    }

    /**
     * Parse configuration from DataMap
     */
    private fun parseConfigFromDataMap(dataMap: DataMap): WearableConfig {
        return WearableConfig(
            autoTranscribe = dataMap.getBoolean("auto_transcribe", true),
            recordingDuration = dataMap.getInt("recording_duration", 30),
            serverUrl = dataMap.getString("server_url", ""),
            language = dataMap.getString("language", "th")
        )
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Wearable Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for wearable device connections and actions"
                enableVibration(false)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show connection notification
     */
    private fun showConnectionNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("WhisperWorks")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show action notification
     */
    private fun showActionNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Recording Action")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}

/**
 * Data classes for message parsing
 */
@Serializable
data class WearableMessage(
    val action: String,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, String> = emptyMap()
)

@Serializable
data class WearableConfig(
    val autoTranscribe: Boolean = true,
    val recordingDuration: Int = 30,
    val serverUrl: String = "",
    val language: String = "th"
)
