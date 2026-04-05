package com.android.system.core.location.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_buffer",
    indices = [Index(value = ["synced", "timestamp"]), Index(value = ["createdAt"])]
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val method: String,
    val signalStrength: Int,
    val isAnomaly: Boolean,
    val anomalyType: String?,
    val batteryLevel: Int,
    val timestamp: Long,
    val isMocked: Boolean,
    val bearing: Double = 0.0,
    val synced: Boolean = false,
    /** Yuborilgan vaqt (sync muvaffaqiyatli bo'lganda) */
    val uploadedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
