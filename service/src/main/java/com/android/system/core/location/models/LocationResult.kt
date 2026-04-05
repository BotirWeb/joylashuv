package com.android.system.core.location.models

data class LocationResult(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val method: LocationMethod,
    val signalStrength: Int,
    val isAnomaly: Boolean,
    val anomalyType: String?,
    val batteryLevel: Int,
    val timestamp: Long,
    val isMocked: Boolean,
    val vpnDetected: Boolean = false,
    val cellOperator: String = "",
    val wifiSsid: String = "",
    val confidencePercent: Int = 0
)
