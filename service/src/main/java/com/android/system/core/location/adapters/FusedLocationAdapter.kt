package com.android.system.core.location.adapters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.android.system.core.location.models.LocationMethod
import com.android.system.core.location.models.LocationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FusedLocationAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fused: FusedLocationProviderClient
) {

    suspend fun getLocation(useBalancedPower: Boolean): LocationResult? = withContext(Dispatchers.IO) {
        try {
            if (!hasFineLocation()) return@withContext null
            val priority = if (useBalancedPower) {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            } else {
                Priority.PRIORITY_HIGH_ACCURACY
            }
            val loc = fused.getCurrentLocation(priority, CancellationTokenSource().token).await()
                ?: fused.lastLocation.await()
            loc?.toLocationResult(LocationMethod.FUSED, placeholderBattery = 0, placeholderSignal = -1)
        } catch (_: Exception) {
            null
        }
    }

    private fun hasFineLocation(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun Location.toLocationResult(
        method: LocationMethod,
        placeholderBattery: Int,
        placeholderSignal: Int
    ): LocationResult {
        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
        return LocationResult(
            lat = latitude,
            lon = longitude,
            accuracy = if (hasAccuracy()) accuracy else 9999f,
            method = method,
            signalStrength = placeholderSignal,
            isAnomaly = false,
            anomalyType = null,
            batteryLevel = placeholderBattery,
            timestamp = time,
            isMocked = mocked
        )
    }
}
