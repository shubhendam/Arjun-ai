package com.example.arjun_ai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight RAM poller for the setup-screen meter (mirrors WatchConnection's
 * polling style). Reports this app's PSS plus device-wide used/total memory.
 */
object MemoryMeter {

    private const val POLL_MS = 2_000L

    data class Mem(
        val appUsedMb: Int = 0,
        val deviceUsedMb: Int = 0,
        val deviceTotalMb: Int = 0,
    ) {
        val deviceUsedFraction: Float
            get() = if (deviceTotalMb > 0) deviceUsedMb.toFloat() / deviceTotalMb else 0f
    }

    private val _mem = MutableStateFlow(Mem())
    val mem: StateFlow<Mem> = _mem

    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                val totalMb = (info.totalMem / (1024 * 1024)).toInt()
                val availMb = (info.availMem / (1024 * 1024)).toInt()
                val appPssMb = try {
                    Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
                } catch (e: Exception) { 0 }
                _mem.value = Mem(
                    appUsedMb = appPssMb,
                    deviceUsedMb = totalMb - availMb,
                    deviceTotalMb = totalMb,
                )
                delay(POLL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
