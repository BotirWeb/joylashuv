package com.android.system.core.location.adapters

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
class CellTowerLocationAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fused: FusedLocationProviderClient
) {

    suspend fun getLocation(): LocationResult? = withContext(Dispatchers.IO) {
        try {
            if (!hasLocationPermission()) return@withContext null

            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val cells = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    emptyList()
                } else {
                    tm?.allCellInfo.orEmpty()
                }
            } catch (_: Exception) {
                emptyList()
            }

            if (cells.isEmpty() && tm == null) return@withContext null

            val dbm = cells.firstNotNullOfOrNull { info ->
                try {
                    @Suppress("DEPRECATION")
                    info.cellSignalStrength?.dbm
                } catch (_: Exception) {
                    null
                }
            } ?: -1

            val loc = try {
                fused.getCurrentLocation(Priority.PRIORITY_LOW_POWER, CancellationTokenSource().token).await()
            } catch (_: Exception) {
                null
            } ?: try {
                fused.lastLocation.await()
            } catch (_: Exception) {
                null
            } ?: run {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                try {
                    lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: Exception) {
                    null
                }
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
                accuracy = if (loc.hasAccuracy()) loc.accuracy.coerceAtLeast(500f) else 1000f,
                method = LocationMethod.CELL,
                cellOperator = tm?.networkOperatorName.orEmpty(),
                signalStrength = dbm,
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

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }
}
