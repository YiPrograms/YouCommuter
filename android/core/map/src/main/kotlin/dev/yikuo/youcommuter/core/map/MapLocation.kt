package dev.yikuo.youcommuter.core.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay

internal data class MapLocationState(val permitted: Boolean, val locating: Boolean, val locate: () -> Unit)

/** Foreground permission only; the Maps SDK owns the blue dot's ongoing updates. */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberMapLocation(onLocated: (LatLng) -> Unit): MapLocationState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(context.hasLocationPermission()) }
    var locating by remember { mutableStateOf(false) }
    var cancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    val located by rememberUpdatedState(onLocated)
    fun message(resource: Int) = Toast.makeText(context, resource, Toast.LENGTH_SHORT).show()
    fun locate() {
        if (!context.hasLocationPermission()) return
        cancellation?.cancel()
        val manager = context.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(manager)) { message(R.string.location_disabled); return }
        val precise = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = manager.getProviders(true)
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
            ?: LocationManager.GPS_PROVIDER.takeIf { precise && it in providers }
        if (provider == null) { message(R.string.location_unavailable); return }
        val signal = CancellationSignal()
        cancellation = signal
        locating = true
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (!signal.isCanceled) {
                    locating = false
                    if (location != null) located(LatLng(location.latitude, location.longitude))
                    else message(R.string.location_unavailable)
                }
            }
        } catch (_: SecurityException) {
            permitted = context.hasLocationPermission(); locating = false
            message(R.string.location_permission_needed)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = context.hasLocationPermission()
        if (permitted) locate() else message(R.string.location_permission_needed)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = context.hasLocationPermission()
            if (event == Lifecycle.Event.ON_STOP) { cancellation?.cancel(); locating = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancellation?.cancel() }
    }
    LaunchedEffect(locating) {
        if (locating) {
            delay(10_000)
            cancellation?.cancel(); locating = false
            message(R.string.location_unavailable)
        }
    }
    return MapLocationState(permitted, locating) {
        if (context.hasLocationPermission()) { permitted = true; locate() }
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private fun Context.hasLocationPermission() =
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
