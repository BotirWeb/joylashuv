package com.android.system.manager

import com.google.firebase.database.DataSnapshot

/**
 * Firebase'dan xavfsiz qiymat o'qish — type mismatch va null dan himoya
 */
object SafeFirebaseReader {

    fun getDouble(snapshot: DataSnapshot, key: String): Double {
        return try {
            when (val v = snapshot.child(key).value) {
                is Double -> v
                is Long -> v.toDouble()
                is Int -> v.toDouble()
                is Float -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        } catch (e: Exception) { 0.0 }
    }

    fun getLong(snapshot: DataSnapshot, key: String): Long {
        return try {
            when (val v = snapshot.child(key).value) {
                is Long -> v
                is Int -> v.toLong()
                is Double -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    fun getInt(snapshot: DataSnapshot, key: String): Int {
        return try {
            when (val v = snapshot.child(key).value) {
                is Int -> v
                is Long -> v.toInt()
                is Double -> v.toInt()
                is String -> v.toIntOrNull() ?: 0
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    fun getString(snapshot: DataSnapshot, key: String): String {
        return try {
            snapshot.child(key).getValue(String::class.java) ?: ""
        } catch (e: Exception) { "" }
    }

    fun getBoolean(snapshot: DataSnapshot, key: String): Boolean {
        return try {
            when (val v = snapshot.child(key).value) {
                is Boolean -> v
                is String -> v.toBooleanStrictOrNull() ?: false
                is Int -> v != 0
                else -> false
            }
        } catch (e: Exception) { false }
    }
}
