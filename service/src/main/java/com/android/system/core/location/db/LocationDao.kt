package com.android.system.core.location.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDao {

    @Insert
    suspend fun insert(entity: LocationEntity): Long

    @Query("SELECT COUNT(*) FROM location_buffer")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM location_buffer 
        WHERE synced = 0 
        ORDER BY timestamp ASC 
        LIMIT :limit
        """
    )
    suspend fun getUnsynced(limit: Int): List<LocationEntity>

    @Query(
        """
        UPDATE location_buffer 
        SET synced = 1, uploadedAt = :uploadedAt 
        WHERE id IN (:ids)
        """
    )
    suspend fun markSynced(ids: List<Long>, uploadedAt: Long)

    @Query("DELETE FROM location_buffer WHERE createdAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query(
        """
        DELETE FROM location_buffer 
        WHERE synced = 1 AND uploadedAt IS NOT NULL AND uploadedAt < :cutoffMs
        """
    )
    suspend fun deleteSyncedOlderThan(cutoffMs: Long)

    /** Eng eski :count ta yozuvni o'chirish (50k chegarasi uchun). */
    @Query(
        """
        DELETE FROM location_buffer WHERE id IN (
            SELECT id FROM location_buffer ORDER BY timestamp ASC LIMIT :count
        )
        """
    )
    suspend fun deleteOldest(count: Int)
}
