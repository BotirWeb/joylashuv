package com.android.system.core.location.adapters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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

/**
 * GPS yo'q yoki ichkarida: ulangan Wi‑Fi (SSID/BSSID) + tarmoq orqali taxminiy joylashuv.
 * Aniq lat/lon uchun Fused last/current location (past prioritet) ishlatiladi, usul WIFI deb belgilanadi.
 */
@Singleton
class WifiLocationAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fused: FusedLocationProviderClient
) {

    suspend fun getLocation(): LocationResult? = withContext(Dispatchers.IO) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@withContext null

            val hasWifiState = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_WIFI_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasWifiState) return@withContext null

            val info = try {
                @Suppress("DEPRECATION")
                wifi.connectionInfo
            } catch (_: Exception) {
                null
            }
            val bssid = info?.bssid
            if (bssid.isNullOrBlank() || bssid == "02:00:00:00:00:00") {
                return@withContext null
            }

            val coarseOk = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (!coarseOk) return@withContext null

            val loc = try {
                fused.getCurrentLocation(Priority.PRIORITY_LOW_POWER, CancellationTokenSource().token).await()
            } catch (_: Exception) {
                null
            } ?: try {
                fused.lastLocation.await()
            } catch (_: Exception) {
                null
            } ?: return@withContext null

            val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                loc.isMock
            } else {
                @Suppress("DEPRECATION")
                loc.isFromMockProvider
            }

            LocationResult(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy.coerceAtLeast(80f) else 200f,
                method = LocationMethod.WIFI,
                wifiSsid = info?.ssid.orEmpty(),
                signalStrength = info.rssi,
                isAnomaly = false,
                anomalyType = null,
                batteryLevel = 0,
                timestamp = loc.time,
                isMocked = mocked
            )
        } catch (_: Exception) {
            null
        }
    }
}
