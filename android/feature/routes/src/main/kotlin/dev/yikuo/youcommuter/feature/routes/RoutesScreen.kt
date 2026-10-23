package dev.yikuo.youcommuter.feature.routes

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.StationLocation
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.IconText
import dev.yikuo.youcommuter.core.ui.pageTransition
import dev.yikuo.youcommuter.core.platform.NotificationCapability
import java.time.DayOfWeek

private enum class RoutePage { List, Editor, Picker }
private val RoutesUiState.page: RoutePage get() = when {
    picker != null -> RoutePage.Picker
    editor != null -> RoutePage.Editor
    else -> RoutePage.List
}

@Composable
fun RoutesRoute(
    coverageRepository: CoverageRepository,
    stationRepository: StationRepository,
    routeRepository: RouteRepository,
    modifier: Modifier = Modifier,
    onEditorVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationCapability by remember { mutableStateOf(NotificationCapability.read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationCapability = NotificationCapability.read(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationCapability = NotificationCapability.read(context) }
    val model: RoutesViewModel = viewModel(factory = viewModelFactory {
        initializer { RoutesViewModel(coverageRepository, stationRepository, routeRepository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.editor != null) { onEditorVisibilityChanged(state.editor != null) }
    DisposableEffect(Unit) { onDispose { onEditorVisibilityChanged(false) } }
    RoutesScreen(
        state = state,
        onAdd = model::addRoute,
        onEdit = model::editRoute,
        onDuplicate = { model.duplicateRoute(it, context.getString(R.string.route_copy_name, it.name)) },
        onSwapStations = model::swapStations,
        onBikeDisplayChanged = model::setBikeDisplay,
        onBikeThresholdChanged = model::setBikeThreshold,
        onDelete = model::requestDelete,
        onEditorDismiss = model::closeEditor,
        onDiscardConfirmed = model::discardEditor,
        onDiscardDismissed = model::keepEditing,
        onNameChanged = model::updateName,
        onDepartureChanged = model::updateDeparture,
        onEstimatedReturnMinutesChanged = model::updateEstimatedReturnMinutes,
        onWeekdayToggled = model::toggleWeekday,
        onPickStation = model::openPicker,
        onRemoveBackup = model::removeBackup,
        onReminderEnabledChanged = { enabled ->
            model.setReminderEnabled(enabled)
            if (enabled && !notificationCapability.permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onReminderLeadChanged = model::setReminderLead,
        onLowBikeThresholdChanged = model::setLowBikeThreshold,
        onLowDockThresholdChanged = model::setLowDockThreshold,
        canPostNotifications = notificationCapability.canPost,
        notificationPermissionGranted = notificationCapability.permissionGranted,
        onRequestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        onSave = model::save,
        onPickerDismiss = model::closePicker,
        onRegionSelected = model::selectRegion,
        onStationQueryChanged = model::updateStationQuery,
        onStationSelected = model::selectStation,
        onStationRetry = model::retryStationSearch,
        onPickerMapToggled = model::togglePickerMap,
        onDeleteConfirmed = model::confirmDelete,
        onDeleteDismissed = model::cancelDelete,
        modifier = modifier,
    )
}

@Composable
fun RoutesScreen(
    state: RoutesUiState,
    onAdd: () -> Unit,
    onEdit: (CommuteRoute) -> Unit,
    onDuplicate: (CommuteRoute) -> Unit,
    onSwapStations: () -> Unit,
    onBikeDisplayChanged: (BikeKind) -> Unit,
    onBikeThresholdChanged: (BikeKind) -> Unit,
    onDelete: (CommuteRoute) -> Unit,
    onEditorDismiss: () -> Unit,
    onDiscardConfirmed: () -> Unit,
    onDiscardDismissed: () -> Unit,
    onNameChanged: (String) -> Unit,
    onDepartureChanged: (String) -> Unit,
    onEstimatedReturnMinutesChanged: (String) -> Unit,
    onWeekdayToggled: (DayOfWeek) -> Unit,
    onPickStation: (StationTarget) -> Unit,
    onRemoveBackup: (BackupGroup, RouteStation) -> Unit,
    onReminderEnabledChanged: (Boolean) -> Unit,
    onReminderLeadChanged: (Int) -> Unit,
    onLowBikeThresholdChanged: (Int) -> Unit,
    onLowDockThresholdChanged: (Int) -> Unit,
    canPostNotifications: Boolean,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSave: () -> Unit,
    onPickerDismiss: () -> Unit,
    onRegionSelected: (String) -> Unit,
    onStationQueryChanged: (String) -> Unit,
    onStationSelected: (StationLocation) -> Unit,
    onStationRetry: () -> Unit,
    onPickerMapToggled: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(state.editor == null) {
        if (state.editor == null) pageStateHolder.removeState("editor")
    }
    BackHandler(enabled = state.editor != null && state.picker == null, onBack = onEditorDismiss)
    BackHandler(enabled = state.picker != null, onBack = onPickerDismiss)
    AnimatedContent(targetState = state, modifier = modifier.fillMaxSize(), contentKey = { it.page },
        transitionSpec = { pageTransition(targetState.page.ordinal > initialState.page.ordinal) },
        label = "route-pages") { pageState ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (pageState.editor == null) pageStateHolder.SaveableStateProvider("routes") {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.routes_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            AppIconButton(AppIconType.Add, stringResource(R.string.add_route), onAdd, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (pageState.routes.isEmpty()) {
                        item { Text(stringResource(R.string.no_routes), Modifier.padding(vertical = 24.dp)) }
                    }
                    items(pageState.routes, key = { it.id }) { route -> RouteCard(route, canPostNotifications, onEdit, onDuplicate, onDelete) }
                }
            }
            pageState.editor?.takeIf { pageState.picker == null }?.let { editor ->
                pageStateHolder.SaveableStateProvider("editor") {
                    RouteEditorScreen(
                        editor = editor,
                        onDismiss = onEditorDismiss,
                        onNameChanged = onNameChanged,
                        onDepartureChanged = onDepartureChanged,
                        onEstimatedReturnMinutesChanged = onEstimatedReturnMinutesChanged,
                        onWeekdayToggled = onWeekdayToggled,
                        onPickStation = onPickStation,
                        onSwapStations = onSwapStations,
                        onBikeDisplayChanged = onBikeDisplayChanged,
                        onBikeThresholdChanged = onBikeThresholdChanged,
                        onRemoveBackup = onRemoveBackup,
                        onReminderEnabledChanged = onReminderEnabledChanged,
                        onReminderLeadChanged = onReminderLeadChanged,
                        onLowBikeThresholdChanged = onLowBikeThresholdChanged,
                        onLowDockThresholdChanged = onLowDockThresholdChanged,
                        canPostNotifications = canPostNotifications,
                        notificationPermissionGranted = notificationPermissionGranted,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onSave = onSave,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            pageState.picker?.let { picker ->
                StationPickerScreen(
                    state = picker,
                    onDismiss = onPickerDismiss,
                    onRegionSelected = onRegionSelected,
                    onQueryChanged = onStationQueryChanged,
                    onStationSelected = onStationSelected,
                    onRetry = onStationRetry,
                    onMapToggled = onPickerMapToggled,
                    modifier = Modifier.fillMaxSize(),
                    active = state.picker != null,
                )
            }
        }
    }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = onDiscardDismissed,
            title = { Text(stringResource(R.string.discard_title)) },
            confirmButton = { TextButton(onClick = onDiscardConfirmed) { Text(stringResource(R.string.discard)) } },
            dismissButton = { TextButton(onClick = onDiscardDismissed) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
    state.pendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text(stringResource(R.string.delete_route_title, route.name)) },
            confirmButton = { TextButton(onClick = onDeleteConfirmed) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = onDeleteDismissed) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RouteCard(
    route: CommuteRoute,
    canPostNotifications: Boolean,
    onEdit: (CommuteRoute) -> Unit,
    onDuplicate: (CommuteRoute) -> Unit,
    onDelete: (CommuteRoute) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(route.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AppIconButton(AppIconType.Copy, stringResource(R.string.copy_route_named, route.name), { onDuplicate(route) })
                AppIconButton(AppIconType.Edit, stringResource(R.string.edit_route_named, route.name), { onEdit(route) })
                AppIconButton(AppIconType.Delete, stringResource(R.string.delete_route_named, route.name), { onDelete(route) },
                    tint = MaterialTheme.colorScheme.error)
            }
            IconText(AppIconType.Bike, route.borrow.primary.name)
            IconText(AppIconType.Dock, route.returns.primary.name)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconText(AppIconType.Clock, route.departure.toString(),
                    description = stringResource(R.string.route_departure, route.departure.toString()))
                route.estimatedReturnMinutes?.let { minutes ->
                    IconText(AppIconType.Timer, stringResource(R.string.duration_short, minutes),
                        description = stringResource(R.string.estimated_return_summary, minutes))
                }
                route.reminderMinutesBefore?.let { minutes ->
                    IconText(
                        if (canPostNotifications) AppIconType.Bell else AppIconType.Warning,
                        if (canPostNotifications) {
                            if (minutes == 0) stringResource(R.string.at_departure) else stringResource(R.string.reminder_lead_short, minutes)
                        } else stringResource(R.string.reminder_unavailable),
                        description = if (canPostNotifications) reminderSummary(minutes) else stringResource(R.string.reminder_unavailable),
                    )
                }
            }
        }
    }
}

@Composable
private fun reminderSummary(minutes: Int): String = if (minutes == 0) {
    stringResource(R.string.reminder_at_departure)
} else {
    pluralStringResource(R.plurals.reminder_before_departure, minutes, minutes)
}

