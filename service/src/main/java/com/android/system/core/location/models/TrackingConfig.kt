package com.android.system.core.location.models

data class TrackingConfig(
    val trackingEnabled: Boolean = true,
    val intervalSeconds: Long = 5,
    val useGps: Boolean = true,
    val useFused: Boolean = true,
    val useWifi: Boolean = true,
    val useCell: Boolean = true,
    val useIp: Boolean = true
)
