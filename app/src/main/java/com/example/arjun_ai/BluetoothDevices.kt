package com.example.arjun_ai

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BluetoothDevices {

    private const val TAG = "BtDevices"
    private const val POLL_MS = 3_000L

    enum class Kind { AUDIO_OUTPUT, BT_OTHER }

    data class Device(
        val name: String,
        val subtitle: String,
        val kind: Kind,
        val isActiveAudio: Boolean
    )

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appCtx = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _permissionGranted.value = hasBtPermission(appCtx)
                _devices.value = collect(appCtx)
                delay(POLL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    @SuppressLint("MissingPermission")
    private fun collect(context: Context): List<Device> {
        Log.d(TAG, "==================== POLL START ====================")
        val out = mutableListOf<Device>()

        // ---- 1) AudioManager outputs (currently active audio sinks) ----
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activeOutputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (e: Exception) {
            Log.e(TAG, "getDevices failed", e); emptyArray()
        }

        val activeBtNames = mutableSetOf<String>()
        Log.d(TAG, "--- AudioManager: ${activeOutputs.size} output(s) ---")
        for ((i, dev) in activeOutputs.withIndex()) {
            Log.d(TAG,
                "[audio $i] type=${dev.type} (${typeName(dev.type)}) " +
                        "productName='${dev.productName}' sink=${dev.isSink}"
            )
            val described = describeAudioDevice(dev)
            if (described == null) {
                Log.d(TAG, "         -> FILTERED (built-in / unsupported type)")
                continue
            }
            val (label, subtitle) = described
            Log.d(TAG, "         -> KEPT '$label' ($subtitle)")
            out += Device(label, subtitle, Kind.AUDIO_OUTPUT, isActiveAudio = true)
            if (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) activeBtNames += label
        }

        // ---- 2) Bonded BT devices (idle but still "connected" in UI) ----
        if (!hasBtPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT NOT GRANTED — skipping bonded scan. " +
                    "Grant via Settings → Apps → Arjun-AI → Permissions → Nearby devices.")
        } else {
            try {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? BluetoothManager
                val adapter: BluetoothAdapter? = btManager?.adapter
                if (adapter == null) {
                    Log.w(TAG, "No BluetoothAdapter")
                } else if (!adapter.isEnabled) {
                    Log.w(TAG, "BluetoothAdapter disabled")
                } else {
                    val bonded = adapter.bondedDevices ?: emptySet()
                    Log.d(TAG, "--- Bonded: ${bonded.size} device(s) ---")
                    for (dev in bonded) {
                        val name = try { dev.name } catch (e: Exception) { null }
                            ?: dev.address ?: "?"
                        val bc = try { dev.bluetoothClass } catch (e: Exception) { null }
                        val major = bc?.majorDeviceClass
                        val majorName = majorClassName(major)
                        Log.d(TAG,
                            "[bonded] name='$name' addr=${dev.address} " +
                                    "majorClass=$major ($majorName)"
                        )

                        // If this device is already shown as the active A2DP sink, skip it.
                        if (activeBtNames.any { it.equals(name, ignoreCase = true) }) {
                            Log.d(TAG, "         -> SKIP (already active audio)")
                            continue
                        }
                        // De-dupe by name across the rest of the list too.
                        if (out.any { it.name.equals(name, ignoreCase = true) }) {
                            Log.d(TAG, "         -> SKIP (duplicate name)")
                            continue
                        }
                        val subtitle = describeBluetoothClass(dev)
                        if (subtitle == null) {
                            Log.d(TAG, "         -> FILTERED (class not in allow list)")
                            continue
                        }
                        Log.d(TAG, "         -> KEPT '$name' ($subtitle)")
                        out += Device(name, subtitle, Kind.BT_OTHER, isActiveAudio = false)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "BT permission revoked mid-call: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "BT enumeration failed", e)
            }
        }

        val final = out.sortedWith(
            compareByDescending<Device> { it.isActiveAudio }.thenBy { it.name.lowercase() }
        )
        Log.d(TAG, "--- FINAL: ${final.size} device(s) ---")
        for (d in final) {
            Log.d(TAG, "  • ${d.name} | ${d.subtitle} | activeAudio=${d.isActiveAudio}")
        }
        Log.d(TAG, "==================== POLL END ======================")
        return final
    }

    private fun describeAudioDevice(dev: AudioDeviceInfo): Pair<String, String>? {
        val name = (dev.productName?.toString() ?: "").ifBlank { null }
        return when (dev.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                (name ?: "Bluetooth headset") to "Bluetooth audio (active)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                (name ?: "Wired headset") to "Wired (with mic)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                (name ?: "Wired headphones") to "Wired"
            AudioDeviceInfo.TYPE_USB_HEADSET ->
                (name ?: "USB headset") to "USB"
            AudioDeviceInfo.TYPE_HEARING_AID ->
                (name ?: "Hearing aid") to "Hearing aid"
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun describeBluetoothClass(dev: BluetoothDevice): String? {
        val bc = try { dev.bluetoothClass } catch (e: Exception) { null }
        val major = bc?.majorDeviceClass
        return when (major) {
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Bluetooth audio (idle)"
            android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            else -> null
        }
    }

    fun hasBtPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ---- debug helpers ----
    private fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "UNKNOWN($t)"
    }

    private fun majorClassName(m: Int?): String = when (m) {
        null -> "null"
        android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "AUDIO_VIDEO"
        android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "COMPUTER"
        android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "PERIPHERAL"
        android.bluetooth.BluetoothClass.Device.Major.PHONE -> "PHONE"
        android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED -> "UNCATEGORIZED"
        android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "WEARABLE"
        else -> "OTHER($m)"
    }
}