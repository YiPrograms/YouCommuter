package dev.yikuo.youcommuter.observation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** Observes system links only. Never scans, connects, discovers services, or writes to a bike. */
internal class BluetoothConnectionObserver(
    private val context: Context,
    private val onLink: (BluetoothLinkObservation) -> Unit,
    private val onPermissionLost: () -> Unit,
    private val onBluetoothOff: () -> Unit,
    private val onSnapshotFailed: () -> Unit,
) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val receivedAt = SystemClock.elapsedRealtime()
            if (!hasPermission(context)) {
                onPermissionLost()
                return
            }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                    onBluetoothOff()
                }
                return
            }
            val kind = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> ObservationEventKind.Connected
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> ObservationEventKind.Disconnected
                else -> return
            }
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val transport = if (Build.VERSION.SDK_INT >= 33) {
                when (intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)) {
                    BluetoothDevice.TRANSPORT_LE -> ObservationTransport.LowEnergy
                    BluetoothDevice.TRANSPORT_BREDR -> ObservationTransport.Classic
                    else -> ObservationTransport.Unknown
                }
            } else ObservationTransport.Unknown
            try {
                reportLink(kind, device, transport, receivedAt)
            } catch (_: SecurityException) {
                onPermissionLost()
            }
        }
    }

    @SuppressLint("MissingPermission") // Rechecked at registration and on every callback; revocation is handled.
    fun start() {
        check(!registered)
        if (!hasPermission(context)) {
            onPermissionLost()
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        try {
            if (manager?.adapter?.isEnabled != true) {
                onBluetoothOff()
                return
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            // Bluetooth broadcasts originate from a privileged process outside the system UID.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            try {
                manager.getConnectedDevices(BluetoothProfile.GATT).forEach {
                    reportLink(ObservationEventKind.InitialConnection, it, ObservationTransport.LowEnergy, SystemClock.elapsedRealtime())
                }
            } catch (_: IllegalStateException) {
                onSnapshotFailed()
            }
        } catch (_: SecurityException) {
            onPermissionLost()
        }
    }

    @SuppressLint("MissingPermission") // Same permission boundary as the link callback; no discovery is initiated.
    private fun reportLink(kind: ObservationEventKind, device: BluetoothDevice, transport: ObservationTransport, receivedAt: Long) {
        val address = device.address
        val info = readCachedDeviceInfo(
            name = { device.name },
            serviceUuids = { device.uuids?.map { it.uuid.toString() } },
            type = {
                when (device.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> ObservationDeviceType.Classic
                    BluetoothDevice.DEVICE_TYPE_LE -> ObservationDeviceType.LowEnergy
                    BluetoothDevice.DEVICE_TYPE_DUAL -> ObservationDeviceType.Dual
                    else -> ObservationDeviceType.Unknown
                }
            },
            bluetoothClass = { device.bluetoothClass?.deviceClass },
        )
        onLink(BluetoothLinkObservation(kind, address, transport, receivedAt, info))
    }

    fun close() {
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    companion object {
        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
