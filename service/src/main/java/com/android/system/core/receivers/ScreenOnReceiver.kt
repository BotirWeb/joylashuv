package com.android.system.core.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenOnReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        // Faqat SCREEN_ON eventlar uchun
        if (intent?.action != Intent.ACTION_SCREEN_ON) return
        
        Log.d("ScreenOnReceiver", "Screen ON event received")
        
        try {
            // SharedPreferences dan boot_pending flag o'qish
            val prefs = context.getSharedPreferences("boot_prefs", Context.MODE_PRIVATE)
            val bootPending = prefs.getBoolean("boot_pending", false)
            
            if (bootPending) {
                // Flag = true bo'lsa - service start qilish va flag clear qilish
                val serviceIntent = Intent(context, com.android.system.core.location.LocationForegroundService::class.java)
                context.startForegroundService(serviceIntent)
                
                // boot_pending flag = false qilish
                prefs.edit().putBoolean("boot_pending", false).apply()
                
                Log.d("ScreenOnReceiver", "✅ Service started after user action")
            } else {
                // Flag = false bo'lsa - service allaqachon ishga tushgan
                Log.d("ScreenOnReceiver", "Service already running")
            }
            
        } catch (e: Exception) {
            Log.e("ScreenOnReceiver", "Failed to handle screen on event: ${e.message}")
        }
    }
}
