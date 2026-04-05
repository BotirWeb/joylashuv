package com.android.system.core.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.android.system.core.location.adapters.IpLocationAdapter
import com.android.system.core.location.db.LocationDao
import com.android.system.core.location.db.LocationEntity
import com.android.system.core.location.models.LocationResult
import com.android.system.core.location.models.TrackingConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: LocationDataSource,
    private val dao: LocationDao,
    private val ipAdapter: IpLocationAdapter
) {
    private val trackingConfigRef by lazy {
        FirebaseDatabase.getInstance().getReference("devices")
    }
    
    private val _trackingConfig = MutableStateFlow(TrackingConfig())
    val trackingConfig: StateFlow<TrackingConfig> = _trackingConfig.asStateFlow()
    
    private var trackingListener: ValueEventListener? = null

    private val lastSuccessMs = AtomicLong(System.currentTimeMillis())
    @Volatile
    private var prevLat: Double? = null
    @Volatile
    private var prevLon: Double? = null
    @Volatile
    private var prevTs: Long? = null
    private var lastVpnCheckMs = 0L
    private var noSignalAlertSent = false
    private var lastBatchSyncMs = 0L

    suspend fun markUserOnline() = withContext(Dispatchers.IO) {
        try {
            val user = FirebaseAuth.getInstance().currentUser ?: return@withContext
            FirebaseDatabase.getInstance()
                .getReference("devices")
                .child(user.uid)
                .updateChildren(
                    mapOf(
                        "email" to (user.email ?: ""),
                        "status" to "online",
                        "lastUpdated" to ServerValue.TIMESTAMP
                    )
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "markUserOnline", e)
        }
    }

    suspend fun markUserOffline() = withContext(Dispatchers.IO) {
        try {
            val user = FirebaseAuth.getInstance().currentUser ?: return@withContext
            FirebaseDatabase.getInstance()
                .getReference("devices")
                .child(user.uid)
                .updateChildren(
                    mapOf(
                        "status" to "offline",
                        "lastUpdated" to ServerValue.TIMESTAMP
                    )
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "markUserOffline", e)
        }
    }

    fun listenTrackingConfig() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cfgNode = snapshot.child("trackingConfig")
                val newConfig = TrackingConfig(
                    trackingEnabled = cfgNode.child("trackingEnabled").getValue(Boolean::class.java) ?: true,
                    intervalSeconds = cfgNode.child("intervalSeconds").getValue(Long::class.java) ?: 30L
                )
                _trackingConfig.value = newConfig
                Log.d(TAG, "TrackingConfig updated: $newConfig")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "listenTrackingConfig cancelled: ${error.message}", error.toException())
            }
        }

        trackingListener?.let { trackingConfigRef.child(uid).removeEventListener(it) }
        trackingListener = listener
        trackingConfigRef.child(uid).addValueEventListener(listener)
    }

    fun stopListeningTrackingConfig() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val listener = trackingListener ?: return
        trackingConfigRef.child(uid).removeEventListener(listener)
        trackingListener = null
    }

    /** Bir sikl: joylashuv olish → anomaliya → onlayn/offline yuborish yoki buffer. */
    suspend fun processTrackingCycle() = withContext(Dispatchers.IO) {
        try {
            maintenancePrune()
            val battery = readBatteryPercent()
            val raw = dataSource.resolveBestLocation(battery)
            val now = System.currentTimeMillis()

            if (raw == null) {
                if (now - lastSuccessMs.get() > NO_SIGNAL_MS) {
                    maybeAlertNoSignal()
                }
                return@withContext
            }

            lastSuccessMs.set(now)
            noSignalAlertSent = false

            val bearingFromLat = prevLat
            val bearingFromLon = prevLon

            var result = enrichBatteryAndSignal(raw, battery)
            result = applySpeedAnomaly(result)
            result = applyVpnAnomalyIfDue(result, now)

            val bearingDeg = if (bearingFromLat != null && bearingFromLon != null) {
                computeBearingDeg(bearingFromLat, bearingFromLon, result.lat, result.lon)
            } else {
                0.0
            }

            if (result.isMocked) {
                pushAdminMockAlert(result)
            }

            if (isOnline()) {
                uploadCurrentToFirebase(result, bearingDeg)
                appendHistory(result, bearingDeg)
            } else {
                dao.insert(result.toEntity(synced = false, bearing = bearingDeg))
                enforceBufferCap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "processTrackingCycle", e)
        }
    }

    /** Internet bor bo'lsa: 200 ta / 15 s interval bilan navbatni yuborish. */
    suspend fun syncOfflineBufferIfNeeded() = withContext(Dispatchers.IO) {
        try {
            if (!isOnline()) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastBatchSyncMs < SYNC_INTERVAL_MS) return@withContext
            lastBatchSyncMs = now

            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext
            val batch = dao.getUnsynced(SYNC_BATCH)
            if (batch.isEmpty()) return@withContext

            val uploadedIds = ArrayList<Long>()
            for (e in batch) {
                try {
                    uploadBufferedEntity(uid, e)
                    uploadedIds.add(e.id)
                } catch (ex: Exception) {
                    Log.w(TAG, "sync row ${e.id} failed: ${ex.message}")
                }
            }
            if (uploadedIds.isNotEmpty()) {
                dao.markSynced(uploadedIds, now)
            }
            maintenancePrune()
        } catch (e: Exception) {
            Log.e(TAG, "syncOfflineBufferIfNeeded", e)
        }
    }

    private suspend fun maintenancePrune() {
        val now = System.currentTimeMillis()
        try {
            dao.deleteOlderThan(now - MAX_BUFFER_AGE_MS)
            dao.deleteSyncedOlderThan(now - DELETE_SYNCED_AFTER_MS)
            val c = dao.count()
            if (c > MAX_ROWS) {
                val excess = c - MAX_ROWS
                if (excess > 0) dao.deleteOldest(excess)
            }
        } catch (e: Exception) {
            Log.e(TAG, "maintenancePrune", e)
        }
    }

    private suspend fun enforceBufferCap() {
        try {
            val c = dao.count()
            if (c > MAX_ROWS) dao.deleteOldest(c - MAX_ROWS)
        } catch (_: Exception) { }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private fun readBatteryPercent(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun readCellSignalDbm(): Int {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return -1
            val cells = tm.allCellInfo ?: return -1
            cells.firstNotNullOfOrNull { info ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.cellSignalStrength?.dbm
                    } else null
                } catch (_: Exception) {
                    null
                }
            } ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun enrichBatteryAndSignal(raw: LocationResult, battery: Int): LocationResult {
        val sig = if (raw.signalStrength != -1) raw.signalStrength else readCellSignalDbm()
        val bat = if (battery >= 0) battery else raw.batteryLevel
        return raw.copy(batteryLevel = bat, signalStrength = sig)
    }

    private fun applySpeedAnomaly(current: LocationResult): LocationResult {
        val pLat = prevLat
        val pLon = prevLon
        val pTs = prevTs
        prevLat = current.lat
        prevLon = current.lon
        prevTs = current.timestamp

        if (pLat == null || pLon == null || pTs == null) return current

        val dtSec = (current.timestamp - pTs) / 1000.0
        if (dtSec <= 0.5) return current

        val distM = haversineMeters(pLat, pLon, current.lat, current.lon)
        val speedMs = distM / dtSec
        val limitMs = HIGH_SPEED_KMH / 3.6
        return if (speedMs > limitMs) {
            current.copy(isAnomaly = true, anomalyType = "HIGH_SPEED_${HIGH_SPEED_KMH.toInt()}KMH")
        } else {
            current
        }
    }

    private suspend fun applyVpnAnomalyIfDue(current: LocationResult, now: Long): LocationResult {
        if (now - lastVpnCheckMs < VPN_CHECK_INTERVAL_MS) return current
        lastVpnCheckMs = now
        return try {
            val (_, mismatch) = ipAdapter.fetchTimezoneOffsetMismatchHours()
            if (mismatch) {
                current.copy(
                    isAnomaly = true,
                    anomalyType = "VPN_TIMEZONE_MISMATCH",
                    vpnDetected = true
                )
            } else {
                current
            }
        } catch (_: Exception) {
            current
        }
    }

    private suspend fun maybeAlertNoSignal() {
        if (noSignalAlertSent) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        noSignalAlertSent = true
        try {
            FirebaseDatabase.getInstance()
                .getReference("alerts")
                .child(uid)
                .push()
                .setValue(
                    mapOf(
                        "type" to "NO_SIGNAL_10MIN",
                        "timestamp" to ServerValue.TIMESTAMP
                    )
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "maybeAlertNoSignal", e)
        }
    }

    private suspend fun pushAdminMockAlert(result: LocationResult) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            FirebaseDatabase.getInstance()
                .getReference("alerts")
                .child(uid)
                .child("mock_location")
                .setValue(
                    mapOf(
                        "lat" to result.lat,
                        "lon" to result.lon,
                        "timestamp" to ServerValue.TIMESTAMP,
                        "method" to result.method.name
                    )
                ).await()
        } catch (e: Exception) {
            Log.e(TAG, "pushAdminMockAlert", e)
        }
    }

    private fun computeBearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    private suspend fun uploadCurrentToFirebase(result: LocationResult, bearing: Double) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: ""

        val payload = mapOf(
            "email" to email,
            "lat" to result.lat,
            "lng" to result.lon,
            "bearing" to bearing,
            "batteryLevel" to result.batteryLevel,
            "signalStrength" to result.signalStrength,
            "accuracy" to result.accuracy,
            "vpnDetected" to result.vpnDetected,
            "cellOperator" to result.cellOperator,
            "wifiSsid" to result.wifiSsid,
            "confidencePercent" to result.confidencePercent,
            "status" to "online",
            "lastUpdated" to ServerValue.TIMESTAMP,
            "locationMethod" to result.method.name,
            "isAnomaly" to result.isAnomaly,
            "anomalyType" to result.anomalyType,
            "isMocked" to result.isMocked
        )
        FirebaseDatabase.getInstance()
            .getReference("devices")
            .child(user.uid)
            .updateChildren(payload)
            .await()
    }

    private suspend fun appendHistory(result: LocationResult, bearing: Double) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val historyPayload = mapOf(
            "lat" to result.lat,
            "lng" to result.lon,
            "bearing" to bearing,
            "timestamp" to ServerValue.TIMESTAMP,
            "method" to result.method.name,
            "isMocked" to result.isMocked,
            "isAnomaly" to result.isAnomaly
        )
        FirebaseDatabase.getInstance()
            .getReference("location_history")
            .child(user.uid)
            .push()
            .setValue(historyPayload)
            .await()
    }

    private suspend fun uploadBufferedEntity(uid: String, e: LocationEntity) {
        val historyPayload = mapOf(
            "lat" to e.lat,
            "lng" to e.lon,
            "bearing" to e.bearing,
            "timestamp" to e.timestamp,
            "method" to e.method,
            "fromBuffer" to true,
            "isMocked" to e.isMocked,
            "isAnomaly" to e.isAnomaly
        )
        FirebaseDatabase.getInstance()
            .getReference("location_history")
            .child(uid)
            .push()
            .setValue(historyPayload)
            .await()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun LocationResult.toEntity(synced: Boolean, bearing: Double = 0.0) = LocationEntity(
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        method = method.name,
        signalStrength = signalStrength,
        isAnomaly = isAnomaly,
        anomalyType = anomalyType,
        batteryLevel = batteryLevel,
        timestamp = timestamp,
        isMocked = isMocked,
        bearing = bearing,
        synced = synced
    )

    companion object {
        private const val TAG = "SYS_CORE_REPO"
        private const val MAX_ROWS = 50_000
        private const val MAX_BUFFER_AGE_MS = 30L * 24 * 60 * 60 * 1000L
        private const val DELETE_SYNCED_AFTER_MS = 7L * 24 * 60 * 60 * 1000L
        private const val SYNC_BATCH = 200
        private const val SYNC_INTERVAL_MS = 15_000L
        private const val NO_SIGNAL_MS = 10 * 60 * 1000L
        private const val VPN_CHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val HIGH_SPEED_KMH = 150.0
    }
}
