package com.android.system.core

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.HiltAndroidApp
import com.android.system.core.crypto.KeyFetchWorker
import com.android.system.core.workers.BootCheckWorker

@HiltAndroidApp
class SystemCoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Firebase anonymous auth
        FirebaseAuth.getInstance().let { auth ->
            if (auth.currentUser == null) {
                auth.signInAnonymously()
            }
        }
        
        // Periodik service health checkni rejalashtirish
        try {
            BootCheckWorker.schedulePeriodicCheck(this)
            Log.d("SystemCoreApplication", "BootCheckWorker scheduled successfully")
        } catch (e: Exception) {
            Log.e("SystemCoreApplication", "Failed to schedule BootCheckWorker: ${e.message}")
        }

        // AES kalitini Firebase'dan bir marta olish
        try {
            KeyFetchWorker.scheduleOnce(this)
            Log.d("SystemCoreApplication", "KeyFetchWorker scheduled successfully")
        } catch (e: Exception) {
            Log.e("SystemCoreApplication", "Failed to schedule KeyFetchWorker: ${e.message}")
        }
    }
}
