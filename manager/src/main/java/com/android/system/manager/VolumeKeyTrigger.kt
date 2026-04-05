package com.android.system.manager

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

object VolumeKeyTrigger {
    private val pattern = mutableListOf<Int>() // 24=UP, 25=DOWN
    private val SECRET = listOf(24, 25, 24, 24) // Vol↑ Vol↓ Vol↑ Vol↑
    private val handler = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null

    fun onVolumeKey(context: Context, keyCode: Int) {
        pattern.add(keyCode)
        if (pattern.size > SECRET.size) pattern.removeAt(0)

        resetRunnable?.let { handler.removeCallbacks(it) }
        resetRunnable = Runnable { pattern.clear() }
        handler.postDelayed(resetRunnable!!, 3000L)

        if (pattern == SECRET) {
            pattern.clear()
            val intent = Intent(context, AdminAuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
