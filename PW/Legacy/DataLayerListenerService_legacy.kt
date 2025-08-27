package com.yourdomain.whisperworks

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

private const val TAG = "ListenerService"

class DataLayerListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (messageEvent.path == "/whisper-works-trigger") {
            val message = String(messageEvent.data)
            Log.d(TAG, "Message received: $message")

            val intent = Intent("com.yourdomain.whisperworks.MESSAGE_RECEIVED")
            intent.putExtra("message", message)
            
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }
}