package com.android.system.core.workers

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

class BootCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    
    override fun doWork(): Result {
        try {
            Log.d("BootCheckWorker", "⏰ Service health check started")
            
            // LocationForegroundService ishga tushganligini tekshirish
            val isRunning = isServiceRunning()
            
            if (isRunning) {
                // Service allaqachon ishlayapti
                Log.d("BootCheckWorker", "✅ Service already running")
                return Result.retry()
            } else {
                // Service o'chgan, qayta ishga tushirish kerak
                Log.d("BootCheckWorker", "Service is dead, restarting...")
                
                // Doze mode tekshirish
                val isDozeActive = isDozeModeActive()
                if (isDozeActive) {
                    Log.w("BootCheckWorker", "⚠️ Doze mode detected")
                }
                
                // Service qayta ishga tushirish
                val serviceIntent = android.content.Intent(
                    applicationContext,
                    com.android.system.core.location.LocationForegroundService::class.java
                )
                applicationContext.startForegroundService(serviceIntent)
                
                Log.d("BootCheckWorker", "✅ Service restarted")
                return Result.retry()
            }
            
        } catch (e: Exception) {
            Log.e("BootCheckWorker", "Failed to check/restart service: ${e.message}")
            return Result.retry()
        }
    }
    
    /**
     * LocationForegroundService ishga tushganligini tekshirish
     */
    private fun isServiceRunning(): Boolean {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            
            runningServices?.any { serviceInfo ->
                serviceInfo.service.className == 
                    com.android.system.core.location.LocationForegroundService::class.java.name
            } ?: false
        } catch (e: Exception) {
            Log.e("BootCheckWorker", "Error checking service status: ${e.message}")
            false
        }
    }
    
    /**
     * Doze mode aktivligini tekshirish (batareya optimizatsiya)
     */
    private fun isDozeModeActive(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isDeviceIdleMode
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("BootCheckWorker", "Error checking doze mode: ${e.message}")
            false
        }
    }
    
    companion object {
        /**
         * Periodik health checkni rejalashtirish (15 daqiqa)
         */
        fun schedulePeriodicCheck(context: Context) {
            try {
                val periodicWorkRequest = PeriodicWorkRequestBuilder<BootCheckWorker>(15, TimeUnit.MINUTES)
                    .build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "boot_check_worker",
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicWorkRequest
                )
                
                Log.d("BootCheckWorker", "⏰ Periodic boot check scheduled (15 min interval)")
            } catch (e: Exception) {
                Log.e("BootCheckWorker", "Failed to schedule periodic check: ${e.message}")
            }
        }
    }
}
