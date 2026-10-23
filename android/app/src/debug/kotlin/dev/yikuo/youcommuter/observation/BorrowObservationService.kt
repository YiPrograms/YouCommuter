package dev.yikuo.youcommuter.observation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.yikuo.youcommuter.YouCommuterApplication
import dev.yikuo.youcommuter.R
import dev.yikuo.youcommuter.core.model.isTerminal
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A bounded, explicitly user-started field capture; never mutates a commute. */
class BorrowObservationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observationId: UUID? = null
    private var observer: BluetoothConnectionObserver? = null
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ExtraObservation)?.toUuidOrNull()
        val state = BorrowObservationStore.state.value
        if (id == null || id != state.id || !state.active) {
            if (observationId == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ActionStop) {
            if (observationId == id) finish(id, ObservationStatus.Stopped, ObservationEventKind.Stopped)
            else {
                BorrowObservationStore.stop(id, ObservationStatus.Stopped, ObservationEventKind.Stopped)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent.action != ActionStart || observationId == id) return START_NOT_STICKY
        // A quick stop/start can reuse this Service instance before onDestroy is delivered.
        scope.coroutineContext.cancelChildren()
        observer?.close()
        observer = null
        finishing = false
        observationId = id
        val deadline = SystemClock.elapsedRealtime() + CaptureDurationMillis
        if (!BluetoothConnectionObserver.hasPermission(this)) {
            finish(id, ObservationStatus.PermissionLost, ObservationEventKind.PermissionLost)
            return START_NOT_STICKY
        }
        try {
            showNotification(id)
        } catch (_: SecurityException) {
            finish(id, ObservationStatus.PermissionLost, ObservationEventKind.PermissionLost)
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            finish(id, ObservationStatus.Failed, ObservationEventKind.Failed)
            return START_NOT_STICKY
        }
        scope.launch {
            val sessionId = state.sessionId
            val sessions = (application as YouCommuterApplication).container.sessions
            var previousPhase: String? = null
            try {
                sessions.observeSession().collect { session ->
                    if (sessionId != null && (session == null || session.id != sessionId || session.phase.isTerminal)) {
                        finish(id, ObservationStatus.SessionEnded, ObservationEventKind.SessionEnded)
                        return@collect
                    }
                    if (observer == null) {
                        val next = BluetoothConnectionObserver(
                            this@BorrowObservationService,
                            onLink = { link ->
                                if (link.receivedElapsedRealtimeMillis >= deadline) {
                                    finish(id, ObservationStatus.TimedOut, ObservationEventKind.TimedOut)
                                } else BorrowObservationStore.recordLink(id, link)
                            },
                            onPermissionLost = { finish(id, ObservationStatus.PermissionLost, ObservationEventKind.PermissionLost) },
                            onBluetoothOff = { finish(id, ObservationStatus.BluetoothOff, ObservationEventKind.BluetoothOff) },
                            onSnapshotFailed = { BorrowObservationStore.record(id, ObservationEventKind.SnapshotFailed) },
                        )
                        observer = next
                        next.start()
                        BorrowObservationStore.running(id)
                    }
                    val phase = session?.phase?.name
                    if (sessionId != null && previousPhase != null && phase != previousPhase) {
                        BorrowObservationStore.record(id, ObservationEventKind.PhaseChanged)
                    }
                    previousPhase = phase
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                finish(id, ObservationStatus.Failed, ObservationEventKind.Failed)
            }
        }
        // Check elapsed real time after sleep; Handler delays alone exclude deep sleep.
        // Revocation without another Bluetooth callback must also end the capture.
        scope.launch {
            while (true) {
                delay(2_000)
                if (SystemClock.elapsedRealtime() >= deadline) {
                    finish(id, ObservationStatus.TimedOut, ObservationEventKind.TimedOut)
                    break
                }
                if (!BluetoothConnectionObserver.hasPermission(this@BorrowObservationService)) {
                    finish(id, ObservationStatus.PermissionLost, ObservationEventKind.PermissionLost)
                    break
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.close()
        observationId?.let { BorrowObservationStore.stop(it, ObservationStatus.Stopped, ObservationEventKind.Stopped) }
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(id: UUID, status: ObservationStatus, reason: ObservationEventKind) {
        if (id != observationId || finishing) return
        finishing = true
        observationId?.let { BorrowObservationStore.stop(it, status, reason) }
        observer?.close()
        scope.coroutineContext.cancelChildren()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification(id: UUID) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Channel, getString(R.string.observation_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, BorrowObservationActivity::class.java).apply {
                BorrowObservationStore.state.value.sessionId?.let { putExtra(ExtraSession, it.toString()) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, BorrowObservationService::class.java).setAction(ActionStop)
                .setData("youcommuter-observation://stop/$id".toUri()).putExtra(ExtraObservation, id.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, Channel)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.observation_running))
            .setContentText(getString(R.string.observation_title))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.observation_stop), stop)
            .build()
        ServiceCompat.startForeground(this, NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        internal const val ExtraSession = "sessionId"
        internal const val ExtraObservation = "observationId"
        private const val ActionStart = "dev.yikuo.youcommuter.observation.START"
        private const val ActionStop = "dev.yikuo.youcommuter.observation.STOP"
        private const val Channel = "borrow-observation"
        private const val NotificationId = 4201
        internal const val CaptureDurationMillis = 15 * 60 * 1000L

        internal fun start(context: Context, sessionId: UUID?) {
            val id = BorrowObservationStore.begin(sessionId) ?: return
            try {
                ContextCompat.startForegroundService(context, Intent(context, BorrowObservationService::class.java)
                    .setAction(ActionStart).putExtra(ExtraObservation, id.toString()))
            } catch (_: SecurityException) {
                BorrowObservationStore.stop(id, ObservationStatus.PermissionLost, ObservationEventKind.PermissionLost)
            } catch (_: IllegalStateException) {
                BorrowObservationStore.stop(id, ObservationStatus.Failed, ObservationEventKind.Failed)
            }
        }

        internal fun stop(context: Context, id: UUID) {
            context.startService(Intent(context, BorrowObservationService::class.java)
                .setAction(ActionStop).putExtra(ExtraObservation, id.toString()))
        }
    }
}

internal fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
