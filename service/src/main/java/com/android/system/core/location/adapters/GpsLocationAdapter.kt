package com.android.system.core.location.adapters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.android.system.core.location.models.LocationMethod
import com.android.system.core.location.models.LocationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GpsLocationAdapter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun getLocation(): LocationResult? = withContext(Dispatchers.IO) {
        try {
            if (!hasFineLocation()) return@withContext null
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@withContext null
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return@withContext null

            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                if (System.currentTimeMillis() - loc.time < 60_000) {
                    return@withContext loc.toGpsResult()
                }
            }

            withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine { cont ->
                    try {
                        lm.requestSingleUpdate(
                            LocationManager.GPS_PROVIDER,
                            { location -> cont.resume(location) },
                            Looper.getMainLooper()
                        )
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }
            }?.toGpsResult()
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

    private fun Location.toGpsResult(): LocationResult {
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
            method = LocationMethod.GPS,
            signalStrength = -1,
            isAnomaly = false,
            anomalyType = null,
            batteryLevel = 0,
            timestamp = time,
            isMocked = mocked
        )
    }
}
