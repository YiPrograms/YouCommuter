package dev.yikuo.youcommuter.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.awaitingBorrow
import dev.yikuo.youcommuter.core.platform.BluetoothBorrowService

/** Permission and service startup belong to a visible activity, regardless of the selected tab. */
@Composable
internal fun BorrowDetectionEffect(sessions: CommuteSessionRepository) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by remember(sessions) { sessions.observeSession() }.collectAsStateWithLifecycle(initialValue = null)
    var permissionCheck by remember { mutableIntStateOf(0) }
    var requestedSession by rememberSaveable { mutableStateOf<String?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionCheck++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionCheck++ }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                    permissionCheck++
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(session?.id, session?.phase, permissionCheck) {
        val current = session?.takeIf { it.phase.awaitingBorrow } ?: return@LaunchedEffect
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31 && !BluetoothBorrowService.hasPermission(context)) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty() && requestedSession != current.id.toString()) {
            requestedSession = current.id.toString()
            request.launch(permissions.toTypedArray())
        } else if (BluetoothBorrowService.hasPermission(context)) {
            BluetoothBorrowService.start(context, current.id)
        }
    }
}
