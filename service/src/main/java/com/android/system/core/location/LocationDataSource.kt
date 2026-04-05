package com.android.system.core.location

import com.android.system.core.location.adapters.CellTowerLocationAdapter
import com.android.system.core.location.adapters.FusedLocationAdapter
import com.android.system.core.location.adapters.GpsLocationAdapter
import com.android.system.core.location.adapters.IpLocationAdapter
import com.android.system.core.location.adapters.WifiLocationAdapter
import com.android.system.core.location.models.LocationResult
import com.android.system.core.location.models.TrackingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prioritet (yuqoridan pastga): Fused → GPS → Wi‑Fi → Cell → IP.
 * Har bir qadam alohida try-catch; xato bo'lsa keyingisiga o'tadi.
 */
@Singleton
class LocationDataSource @Inject constructor(
    private val fusedAdapter: FusedLocationAdapter,
    private val gpsAdapter: GpsLocationAdapter,
    private val wifiAdapter: WifiLocationAdapter,
    private val cellAdapter: CellTowerLocationAdapter,
    private val ipAdapter: IpLocationAdapter
) {
    @Volatile
    private var trackingConfig: TrackingConfig = TrackingConfig()

    fun updateTrackingConfig(config: TrackingConfig) {
        trackingConfig = config
    }

    fun getTrackingConfigSnapshot(): TrackingConfig = trackingConfig

    suspend fun resolveBestLocation(batteryPercent: Int): LocationResult? = withContext(Dispatchers.IO) {
        val useBalancedPower = batteryPercent in 0..14
        val cfg = trackingConfig

        if (!cfg.trackingEnabled) return@withContext null

        if (cfg.useFused) {
            val fused = tryOrNull { fusedAdapter.getLocation(useBalancedPower) }
            if (fused != null) return@withContext fused.copy(confidencePercent = 95)
        }

        if (cfg.useGps) {
            val gps = tryOrNull { gpsAdapter.getLocation() }
            if (gps != null) return@withContext gps.copy(confidencePercent = 90)
        }

        if (cfg.useWifi) {
            val wifi = tryOrNull { wifiAdapter.getLocation() }
            if (wifi != null) return@withContext wifi.copy(confidencePercent = 70)
        }

        if (cfg.useCell) {
            val cell = tryOrNull { cellAdapter.getLocation() }
            if (cell != null) return@withContext cell.copy(confidencePercent = 50)
        }

        if (cfg.useIp) {
            val ip = tryOrNull { ipAdapter.getLocation() }
            if (ip != null) return@withContext ip.copy(confidencePercent = 30)
        }

        null
    }

    private inline fun tryOrNull(block: () -> LocationResult?): LocationResult? {
        return try {
            block()
        } catch (_: Exception) {
            null
        }
    }
}
