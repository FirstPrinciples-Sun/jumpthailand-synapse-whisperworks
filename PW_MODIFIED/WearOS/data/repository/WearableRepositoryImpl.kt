package com.yourdomain.whisperworks.wear.data.repository

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.yourdomain.whisperworks.wear.presentation.WearableConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

/**
 * Implementation of WearableRepository using the Wearable Data Layer API
 */
class WearableRepositoryImpl @Inject constructor(
    private val context: Context
) : WearableRepository {

    companion object {
        private const val TAG = "WearableRepository"
        private const val HOST_CAPABILITY = "whisper_works_host"
        private const val TRIGGER_PATH = "/whisper-works-trigger"
    }

    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }

    private val _connectionStatus = MutableStateFlow(WearableConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<WearableConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<Node>>(emptyList())
    override val connectedDevices: StateFlow<List<Node>> = _connectedDevices.asStateFlow()

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { capabilityInfo ->
        Timber.d("Capability changed: ${capabilityInfo.name}")
        if (capabilityInfo.name == HOST_CAPABILITY) {
            updateConnectedNodes(capabilityInfo.nodes)
        }
    }

    init {
        // Register listener for capability changes
        capabilityClient.addListener(capabilityListener, HOST_CAPABILITY)
        checkConnectionStatus()
    }

    override fun checkConnectionStatus() {
        _connectionStatus.value = WearableConnectionStatus.CONNECTING

        // Asynchronously check for connected nodes
        capabilityClient.getCapability(HOST_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilityInfo ->
                Timber.d("Successfully fetched capability info")
                updateConnectedNodes(capabilityInfo.nodes)
            }
            .addOnFailureListener { e ->
                Timber.e(e, "Failed to get capability info")
                _connectionStatus.value = WearableConnectionStatus.DISCONNECTED
                _connectedDevices.value = emptyList()
            }
    }

    override suspend fun sendTriggerToPhone(triggerType: String): Result<Unit> {
        val nodes = connectedDevices.value

        if (nodes.isEmpty()) {
            Timber.w("Cannot send trigger, no connected nodes found")
            return Result.failure(IllegalStateException("No connected phone found"))
        }

        return try {
            // Send trigger to the first available node
            val nodeId = nodes.first().id
            val messageData = triggerType.toByteArray(Charsets.UTF_8)

            messageClient.sendMessage(nodeId, TRIGGER_PATH, messageData).await()
            Timber.d("Successfully sent trigger '$triggerType' to node $nodeId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send trigger message")
            Result.failure(e)
        }
    }

    private fun updateConnectedNodes(nodes: Set<Node>) {
        val nearbyNodes = nodes.filter { it.isNearby }
        _connectedDevices.value = nearbyNodes

        if (nearbyNodes.isNotEmpty()) {
            _connectionStatus.value = WearableConnectionStatus.CONNECTED
            Timber.d("Connected to ${nearbyNodes.size} nodes: ${nearbyNodes.joinToString { it.displayName }}")
        } else {
            _connectionStatus.value = WearableConnectionStatus.DISCONNECTED
            Timber.d("No nearby nodes found")
        }
    }

    /**
     * Clean up resources when the repository is no longer needed
     */
    fun cleanup() {
        capabilityClient.removeListener(capabilityListener)
        Timber.d("WearableRepository cleaned up")
    }
}
