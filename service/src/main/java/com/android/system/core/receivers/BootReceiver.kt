package com.android.system.core.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        Log.d("BootReceiver", "Received action: ${intent?.action}")
        
        // Qurilma turini aniqlash
        val deviceType = detectDeviceType()
        Log.d("BootReceiver", "Device type detected: $deviceType")
        
        // MIUI/EMUI - flag set qilish
        if (deviceType in listOf("MIUI", "EMUI")) {
            val prefs = context.getSharedPreferences("boot_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("boot_pending", true).apply()
            Log.d("BootReceiver", "MIUI/EMUI detected - boot_pending flag set")
        } else {
            // Boshqa tizimlar - service start qilish
            try {
                val serviceIntent = Intent(context, com.android.system.core.location.LocationForegroundService::class.java)
                context.startForegroundService(serviceIntent)
                Log.d("BootReceiver", "Service started directly")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service: ${e.message}")
            }
        }
    }
    
    /**
     * Qurilma turi (MIUI, Samsung, EMUI, boshqa) ni aniqlash
     */
    private fun detectDeviceType(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        val brand = Build.BRAND.lowercase()
        
        return when {
            // MIUI uchun tekshirish
            "miui" in fingerprint || "xiaomi" in manufacturer || "redmi" in brand -> "MIUI"
            
            // Samsung uchun tekshirish
            "samsung" in manufacturer -> "SAMSUNG"
            
            // EMUI/Huawei/Honor uchun tekshirish
            "huawei" in fingerprint || "honor" in fingerprint || "emui" in fingerprint -> "EMUI"
            
            // OnePlus uchun tekshirish
            "oneplus" in manufacturer || "oxygen" in fingerprint -> "ONEPLUS"
            
            // Oppo/Realme uchun tekshirish
            "oppo" in manufacturer || "realme" in manufacturer || "coloros" in fingerprint -> "OPPO"
            
            // Vivo uchun tekshirish
            "vivo" in manufacturer || "funtouch" in fingerprint -> "VIVO"
            
            // Boshqa standart Android tizimlari
            else -> "VANILLA"
        }
    }
}
