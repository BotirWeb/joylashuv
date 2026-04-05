package com.android.system.core.location.adapters

import com.android.system.core.location.models.LocationMethod
import com.android.system.core.location.models.LocationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IpLocationAdapter @Inject constructor() {

    suspend fun getLocation(): LocationResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://ip-api.com/json/?fields=status,message,lat,lon,timezone,mobile,query")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
            }
            conn.inputStream.use { ins ->
                val text = ins.bufferedReader().readText()
                val json = JSONObject(text)
                if (json.optString("status") != "success") return@withContext null
                val lat = json.optDouble("lat", Double.NaN)
                val lon = json.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@withContext null
                LocationResult(
                    lat = lat,
                    lon = lon,
                    accuracy = 5000f,
                    method = LocationMethod.IP,
                    signalStrength = -1,
                    isAnomaly = false,
                    anomalyType = null,
                    batteryLevel = 0,
                    timestamp = System.currentTimeMillis(),
                    isMocked = false
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTimezoneOffsetMismatchHours(): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://ip-api.com/json/?fields=status,timezone")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            conn.inputStream.use { ins ->
                val json = JSONObject(ins.bufferedReader().readText())
                if (json.optString("status") != "success") return@withContext Pair(null, false)
                val tzId = json.optString("timezone", "")
                if (tzId.isBlank()) return@withContext Pair(null, false)
                val deviceOffset = java.util.TimeZone.getDefault()
                    .getOffset(System.currentTimeMillis())
                val ipTz = java.util.TimeZone.getTimeZone(tzId)
                val ipOffset = ipTz.getOffset(System.currentTimeMillis())
                val mismatch = kotlin.math.abs(deviceOffset - ipOffset) > TimeUnit.HOURS.toMillis(1)
                Pair(tzId, mismatch)
            }
        } catch (_: Exception) {
            Pair(null, false)
        }
    }
}
