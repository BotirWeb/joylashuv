package com.android.system.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.system.core.location.LocationForegroundService

class DeviceEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val bootCompleted = action == Intent.ACTION_BOOT_COMPLETED
        val quickboot = action == "android.intent.action.QUICKBOOT_POWERON"

        if (bootCompleted || quickboot) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val serviceIntent = Intent(context, LocationForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: SecurityException) {
                    Log.e("DeviceEventReceiver", "Service start blocked: ${e.message}")
                }
            }, 3000)
        }
    }
}
