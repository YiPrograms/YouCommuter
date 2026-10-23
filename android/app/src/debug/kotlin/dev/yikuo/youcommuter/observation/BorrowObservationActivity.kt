package dev.yikuo.youcommuter.observation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import dev.yikuo.youcommuter.YouCommuterApplication
import dev.yikuo.youcommuter.R
import dev.yikuo.youcommuter.ui.YouCommuterTheme
import dev.yikuo.youcommuter.core.model.isTerminal
import dev.yikuo.youcommuter.core.domain.YouBikeBorrowDetection
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BorrowObservationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawSessionId = intent.getStringExtra(BorrowObservationService.ExtraSession)
        val sessionId = rawSessionId?.toUuidOrNull()
        if (rawSessionId != null && sessionId == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val sessions = (application as YouCommuterApplication).container.sessions
        setContent {
            YouCommuterTheme {
                val state by BorrowObservationStore.state.collectAsStateWithLifecycle()
                val session by remember { sessions.observeSession() }.collectAsStateWithLifecycle(initialValue = null)
                var readiness by remember { mutableStateOf(readiness()) }
                var error by remember { mutableStateOf<Int?>(null) }
                var exportBody by remember { mutableStateOf<String?>(null) }
                var exporting by remember { mutableStateOf(false) }
                val currentSession = session?.takeIf { it.id == sessionId && !it.phase.isTerminal }
                val matching = state.sessionId == sessionId
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { readiness = readiness() }
                LaunchedEffect(state.status) { readiness = readiness() }
                val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    readiness = readiness()
                    if (readiness == ObservationReadiness.Ready) BorrowObservationService.start(this, sessionId)
                }
                val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    val body = exportBody
                    exportBody = null
                    if (uri != null && body != null) {
                        exporting = true
                        lifecycleScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                runCatching {
                                    checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                                }.isSuccess
                            }
                            exporting = false
                            if (!success) error = R.string.observation_export_failed
                        }
                    }
                }
                BorrowObservationScreen(
                    state = if (matching) state else BorrowObservationState(),
                    readiness = readiness,
                    canStart = (sessionId == null || currentSession != null) && !state.active,
                    canExport = matching && state.events.isNotEmpty() && !state.active && !exporting,
                    otherCapture = state.active && !matching,
                    error = error,
                    onBack = ::finish,
                    onStart = {
                        error = null
                        readiness = readiness()
                        if (readiness == ObservationReadiness.PermissionRequired) {
                            requestPermissions.launch(buildList {
                                if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }.toTypedArray())
                        } else if (readiness == ObservationReadiness.Ready) {
                            BorrowObservationService.start(this, sessionId)
                        }
                    },
                    onStop = { state.id?.let { BorrowObservationService.stop(this, it) } },
                    onMark = { kind -> state.id?.takeIf { matching }?.let { BorrowObservationStore.record(it, kind) } },
                    onClear = { if (matching) BorrowObservationStore.clear() },
                    onExport = {
                        exportBody = BorrowObservationStore.export()
                        export.launch("youcommuter-borrow-observation.json")
                    },
                    onSettings = {
                        val settings = if (readiness == ObservationReadiness.BluetoothOff) Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                        startActivity(settings)
                    },
                    onYouBike = {
                        try {
                            val launch = packageManager.getLaunchIntentForPackage("tw.com.youbike.plus")
                            if (launch == null) error = R.string.observation_youbike_missing else startActivity(launch)
                        } catch (_: ActivityNotFoundException) {
                            error = R.string.observation_youbike_missing
                        }
                    },
                )
            }
        }
    }

    @SuppressLint("MissingPermission") // Permission checked before accessing adapter state.
    private fun readiness(): ObservationReadiness {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return ObservationReadiness.Unsupported
        if (!BluetoothConnectionObserver.hasPermission(this)) return ObservationReadiness.PermissionRequired
        return try {
            if (adapter.isEnabled) ObservationReadiness.Ready else ObservationReadiness.BluetoothOff
        } catch (_: SecurityException) {
            ObservationReadiness.PermissionRequired
        }
    }
}

internal enum class ObservationReadiness { Ready, PermissionRequired, BluetoothOff, Unsupported }

@Composable
private fun BorrowObservationScreen(
    state: BorrowObservationState,
    readiness: ObservationReadiness,
    canStart: Boolean,
    canExport: Boolean,
    otherCapture: Boolean,
    error: Int?,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: (ObservationEventKind) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onYouBike: () -> Unit,
) {
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIconButton(AppIconType.Back, stringResource(R.string.observation_back), onBack)
                    Text(stringResource(R.string.observation_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    AppIconButton(AppIconType.Backup, stringResource(R.string.observation_export), onExport, enabled = canExport)
                    AppIconButton(AppIconType.Delete, stringResource(R.string.observation_clear), onClear,
                        enabled = !state.active && state.events.isNotEmpty())
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(statusLabel(state, readiness, otherCapture, canStart)), style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.active) {
                                Button(onClick = onStop) { Text(stringResource(R.string.observation_stop)) }
                            } else {
                                Button(onClick = onStart, enabled = canStart && readiness != ObservationReadiness.Unsupported &&
                                    readiness != ObservationReadiness.BluetoothOff) {
                                    AppIcon(AppIconType.Play)
                                    Text(stringResource(R.string.observation_start), Modifier.padding(start = 4.dp))
                                }
                            }
                            if (readiness in setOf(ObservationReadiness.BluetoothOff, ObservationReadiness.PermissionRequired)) {
                                OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.observation_settings)) }
                            }
                            if (state.status == ObservationStatus.Running) {
                                OutlinedButton(onClick = onYouBike) { Text("YouBike") }
                            }
                        }
                        // Necessary to distinguish field notes from confirmed commute actions.
                        Text(stringResource(R.string.observation_notes_only), style = MaterialTheme.typography.bodySmall)
                        if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (state.status == ObservationStatus.Running) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(ObservationEventKind.BorrowSucceeded, ObservationEventKind.BorrowFailed,
                            ObservationEventKind.TemporaryLock, ObservationEventKind.ReturnSucceeded).forEach { kind ->
                            OutlinedButton(onClick = { onMark(kind) }) { Text(stringResource(eventLabel(kind))) }
                        }
                    }
                }
            }
            items(state.events.asReversed(), key = { it.number }) { event ->
                ObservationEventRow(event, state.id)
            }
        }
    }
}

@Composable
private fun ObservationEventRow(event: ObservationEvent, observationId: UUID?) {
    var expanded by rememberSaveable(observationId, event.number) { mutableStateOf(false) }
    val info = event.deviceInfo
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatElapsed(event.elapsedMillis), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(eventLabel(event.kind)), Modifier.weight(1f))
            if (YouBikeBorrowDetection.matches(info?.name)) AppIcon(AppIconType.Bike, contentDescription = "YouBike")
            if (event.device != null) Text(stringResource(R.string.observation_device, event.device) + when (event.transport) {
                ObservationTransport.LowEnergy -> " · LE"
                ObservationTransport.Classic -> " · BR/EDR"
                ObservationTransport.Unknown -> ""
            }, style = MaterialTheme.typography.labelMedium)
            if (info != null) AppIconButton(
                if (expanded) AppIconType.ExpandLess else AppIconType.ExpandMore,
                stringResource(if (expanded) R.string.observation_hide_device_info else R.string.observation_device_info),
                { expanded = !expanded },
            )
        }
        if (expanded && info != null) {
            val unknown = stringResource(R.string.observation_unknown)
            Text(stringResource(R.string.observation_device_name, info.name ?: unknown), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.observation_device_type, when (info.type) {
                ObservationDeviceType.Unknown -> unknown
                ObservationDeviceType.Classic -> "BR/EDR"
                ObservationDeviceType.LowEnergy -> "LE"
                ObservationDeviceType.Dual -> "BR/EDR + LE"
            }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.observation_device_class, info.bluetoothClass?.let { "0x${it.toString(16)}" } ?: unknown),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.observation_cached_uuids,
                info.cachedServiceUuids?.joinToString("\n")?.ifEmpty { stringResource(R.string.observation_empty) } ?: unknown),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusLabel(state: BorrowObservationState, readiness: ObservationReadiness, otherCapture: Boolean, canStart: Boolean): Int = when {
    otherCapture -> R.string.observation_other_session
    state.active -> if (state.status == ObservationStatus.Starting) R.string.observation_starting else R.string.observation_running
    readiness == ObservationReadiness.Unsupported -> R.string.observation_unsupported
    readiness == ObservationReadiness.PermissionRequired -> R.string.observation_permission
    readiness == ObservationReadiness.BluetoothOff -> R.string.observation_bluetooth_off
    state.status == ObservationStatus.Idle && !canStart -> R.string.observation_session_ended
    state.status == ObservationStatus.Idle -> R.string.observation_ready
    state.status == ObservationStatus.Failed -> R.string.observation_failed
    state.status == ObservationStatus.PermissionLost -> R.string.observation_permission
    state.status == ObservationStatus.TimedOut -> R.string.observation_timeout
    state.status == ObservationStatus.SessionEnded -> R.string.observation_session_ended
    state.status == ObservationStatus.BluetoothOff -> R.string.observation_bluetooth_off
    else -> R.string.observation_stopped
}

private fun eventLabel(kind: ObservationEventKind): Int = when (kind) {
    ObservationEventKind.Started -> R.string.observation_started
    ObservationEventKind.InitialConnection -> R.string.observation_initial_connection
    ObservationEventKind.Connected -> R.string.observation_connected
    ObservationEventKind.Disconnected -> R.string.observation_disconnected
    ObservationEventKind.BluetoothOff -> R.string.observation_bluetooth_off
    ObservationEventKind.BorrowSucceeded -> R.string.observation_borrow_succeeded
    ObservationEventKind.BorrowFailed -> R.string.observation_borrow_failed
    ObservationEventKind.TemporaryLock -> R.string.observation_temporary_lock
    ObservationEventKind.ReturnSucceeded -> R.string.observation_return_succeeded
    ObservationEventKind.PhaseChanged -> R.string.observation_phase_changed
    ObservationEventKind.SnapshotFailed -> R.string.observation_snapshot_failed
    ObservationEventKind.PermissionLost -> R.string.observation_permission
    ObservationEventKind.Stopped -> R.string.observation_stopped
    ObservationEventKind.TimedOut -> R.string.observation_timeout
    ObservationEventKind.SessionEnded -> R.string.observation_session_ended
    ObservationEventKind.Failed -> R.string.observation_failed
}

private fun formatElapsed(millis: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d",
    millis / 60_000, millis / 1_000 % 60, millis % 1_000)
