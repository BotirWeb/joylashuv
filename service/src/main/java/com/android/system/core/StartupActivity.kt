package com.android.system.core

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class StartupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DEBUG_START"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "StartupActivity onCreate")
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // First check if GPS is enabled
        if (!isGpsEnabled()) {
            showGpsDisabledDialog()
            return
        }
        
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "All permissions granted, starting service")
            startLocationService()
        } else {
            Log.d(TAG, "Requesting permissions: $missing")
            showPermissionDialog(missing)
        }
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    
    private fun showGpsDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("GPS Required")
            .setMessage("Location tracking requires GPS to be enabled. Please enable GPS in settings.")
            .setPositiveButton("Enable GPS") { _, _ ->
                openLocationSettings()
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "GPS is required for location tracking", Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showPermissionDialog(missingPermissions: List<String>) {
        val message = buildString {
            append("This app requires the following permissions for location tracking:\n\n")
            missingPermissions.forEach { permission ->
                when (permission) {
                    Manifest.permission.ACCESS_FINE_LOCATION -> append("• Precise Location (GPS)\n")
                    Manifest.permission.ACCESS_COARSE_LOCATION -> append("• Approximate Location (Network)\n")
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> append("• Background Location (Android 10+)\n")
                    Manifest.permission.POST_NOTIFICATIONS -> append("• Notifications (Android 13+)\n")
                }
            }
            append("\nThese permissions are required to track courier location in real-time.")
        }
        
        AlertDialog.Builder(this)
            .setTitle("Location Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant Permissions") { _, _ ->
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "Permissions are required for location tracking", Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
        finish()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d(TAG, "All permissions granted after request")
                // Check GPS again after permissions are granted
                if (!isGpsEnabled()) {
                    showGpsDisabledDialog()
                } else {
                    startLocationService()
                }
            } else {
                Log.e(TAG, "Permissions denied, opening app settings")
                openAppSettings()
            }
        }
    }

    private fun startLocationService() {
        try {
            val intent = Intent(this, com.android.system.core.location.LocationForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
        } finally {
            finish()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        finish()
    }
}
