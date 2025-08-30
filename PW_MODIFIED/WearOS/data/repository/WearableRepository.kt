package com.yourdomain.whisperworks.wear.data.repository

import com.google.android.gms.wearable.Node
import com.yourdomain.whisperworks.wear.presentation.WearableConnectionStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for handling communication with the host phone device
 */
interface WearableRepository {

    /**
     * The current connection status with the host phone
     */
    val connectionStatus: StateFlow<WearableConnectionStatus>

    /**
     * A list of currently connected nodes (phones)
     */
    val connectedDevices: StateFlow<List<Node>>

    /**
     * Sends a trigger message to the phone to start recording
     * @param triggerType A string indicating what caused the trigger (e.g., "voice_trigger", "manual_trigger")
     * @return A Result indicating success or failure
     */
    suspend fun sendTriggerToPhone(triggerType: String): Result<Unit>

    /**
     * Manually checks and updates the connection status
     */
    fun checkConnectionStatus()
}
