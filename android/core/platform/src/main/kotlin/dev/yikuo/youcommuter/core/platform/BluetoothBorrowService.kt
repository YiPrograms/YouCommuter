package dev.yikuo.youcommuter.core.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.InvalidCommuteTransition
import dev.yikuo.youcommuter.core.domain.StaleCommuteCommand
import dev.yikuo.youcommuter.core.domain.YouBikeBorrowDetection
import dev.yikuo.youcommuter.core.domain.awaitingBorrow
import dev.yikuo.youcommuter.core.model.CommuteSession
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

interface BorrowDetectionDataProvider {
    val sessionsForBorrowDetection: CommuteSessionRepository
}

/** Observes new system connections during a user-started commute. Never scans or connects. */
class BluetoothBorrowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var targetSessionId: UUID? = null
    private var currentSession: CommuteSession? = null
    private var registered = false
    private var finishing = false
    private val sessions get() = (application as BorrowDetectionDataProvider).sessionsForBorrowDetection

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Checked for each event; revocation stops this service.
        override fun onReceive(context: Context, intent: Intent) {
            if (!hasPermission(context)) {
                finish()
                return
            }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) in
                    setOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF)) finish()
                return
            }
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED || finishing) return
            val session = currentSession ?: return
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val name = try {
                device.name
            } catch (_: SecurityException) {
                finish()
                return
            } catch (_: RuntimeException) {
                return // An unavailable name is not evidence of borrowing.
            }
            val command = YouBikeBorrowDetection.borrowCommand(session, name, Instant.now()) ?: return
            scope.launch {
                try {
                    sessions.dispatch(session.id, command)
                    if (targetSessionId == session.id) finish()
                } catch (_: StaleCommuteCommand) {
                    // A newer selection, manual action, or replacement session takes precedence.
                } catch (_: InvalidCommuteTransition) {
                    // The session may have been cleared while the command was queued.
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(Tag, "Could not record Bluetooth borrowing", error)
                    if (targetSessionId == session.id) finish()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // Connect permission checked before accessing Bluetooth state.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ExtraSession)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null) {
            if (targetSessionId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val alreadyObserving = id == targetSessionId && !finishing
        if (!alreadyObserving) {
            scope.coroutineContext.cancelChildren()
            closeReceiver()
            targetSessionId = id
            currentSession = null
            finishing = false
        }
        try {
            // Every startForegroundService request needs promotion, including reused Service instances.
            // Bluetooth can turn off between the activity's check and this callback.
            showNotification(currentSession)
            if (!hasPermission(this) || getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) {
                finish()
                return START_NOT_STICKY
            }
        } catch (_: SecurityException) {
            finish()
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            finish()
            return START_NOT_STICKY
        }
        if (alreadyObserving) return START_NOT_STICKY
        scope.launch {
            try {
                sessions.observeSession().collect { session ->
                    if (targetSessionId != id || finishing) return@collect
                    if (session?.id != id || !session.phase.awaitingBorrow) {
                        finish()
                        return@collect
                    }
                    currentSession = session
                    showNotification(session)
                    if (!registered) {
                        // Bluetooth system broadcasts come from a privileged process outside the system UID.
                        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
                            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        }
                        ContextCompat.registerReceiver(this@BluetoothBorrowService, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                        registered = true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(Tag, "Bluetooth borrowing stopped", error)
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeReceiver()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        currentSession = null
        closeReceiver()
        scope.coroutineContext.cancelChildren()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeReceiver() {
        if (registered) unregisterReceiver(receiver)
        registered = false
    }

    private fun showNotification(session: CommuteSession?) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Channel, getString(R.string.borrow_detection_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, Channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.borrow_detection_waiting))
            .setContentText(session?.selectedBorrow?.name)
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        ServiceCompat.startForeground(this, NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        private const val Tag = "BluetoothBorrow"
        private const val ExtraSession = "sessionId"
        private const val Channel = "borrow-detection"
        private const val NotificationId = 4202

        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** Call only while the activity is resumed; background callbacks never start a service. */
        @SuppressLint("MissingPermission") // Permission and adapter state are checked immediately before startup.
        fun start(context: Context, sessionId: UUID) {
            try {
                if (!hasPermission(context) || context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) return
                ContextCompat.startForegroundService(context, Intent(context, BluetoothBorrowService::class.java)
                    .putExtra(ExtraSession, sessionId.toString()))
            } catch (_: SecurityException) {
                // Missing permission leaves the manual borrow action available.
            } catch (_: IllegalStateException) {
                // Includes foreground-service start restrictions during an activity transition.
            }
        }
    }
}
