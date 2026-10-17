package dev.hkgill.gillspeak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/** API 31+ selects a communication output; Android selects its matching input, including BLE. */
@Suppress("DEPRECATION")
internal class AndroidMicrophoneRouting(private val context: Context) : RoutingPlatform {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var previousMode: Int? = null
    private var communicationRequested = false
    private var scoRequested = false
    private var receiverRegistered = false
    @Volatile private var scoConnected = false
    private val sco = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scoConnected = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
    }

    fun input(id: Int): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    override fun inputs() = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.micDevice() }
    override fun outputs() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.micDevice() }
    override fun communications(): List<MicDevice> = if (Build.VERSION.SDK_INT >= 31) {
        audio.availableCommunicationDevices.map { it.micDevice() }
    } else {
        // Android 11 exposes the HFP endpoint through getDevices. A2DP alone has no microphone.
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            .map { it.micDevice() }
    }

    override fun currentCommunication(): Int? = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.id else null

    override fun requestCommunication(device: MicDevice): Boolean {
        // Do not steal the audio mode from a call or another communication app.
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val endpoint = audio.availableCommunicationDevices.firstOrNull { it.id == device.id } ?: return false
            communicationRequested = true // clear even when the platform rejects or throws
            return audio.setCommunicationDevice(endpoint)
        }
        if (!audio.isBluetoothScoAvailableOffCall) return false
        val sticky = context.registerReceiver(sco, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        receiverRegistered = true
        scoConnected = sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
        scoRequested = true
        audio.startBluetoothSco()
        return true
    }

    override fun communicationReady(device: MicDevice): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        audio.communicationDevice?.id == device.id
    } else scoConnected

    override fun releaseCommunication() {
        // No global speakerphone/SCO toggles: those could undo another app's routing.
        if (communicationRequested && Build.VERSION.SDK_INT >= 31) {
            communicationRequested = false
            runCatching { audio.clearCommunicationDevice() }
        }
        if (scoRequested) {
            scoRequested = false
            runCatching { audio.stopBluetoothSco() }
        }
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { context.unregisterReceiver(sco) }
        }
        previousMode?.let { mode ->
            previousMode = null
            // Release our mode request even if a phone call has since taken priority. AudioManager tracks owners.
            runCatching { audio.mode = mode }
        }
    }
}

internal fun AudioDeviceInfo.micDevice() = MicDevice(id, when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.PHONE
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicKind.SCO
    AudioDeviceInfo.TYPE_BLE_HEADSET -> MicKind.BLE
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> MicKind.OUTPUT_ONLY
    else -> MicKind.OTHER
}, isSource)

internal object AndroidRouteClock : RouteClock {
    override fun now() = SystemClock.uptimeMillis()
    override fun pause() = Thread.sleep(50)
}
