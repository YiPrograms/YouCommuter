package dev.yikuo.youcommuter.feature.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.format.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.yikuo.youcommuter.core.model.AvailabilityThresholdRange
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.IconText
import dev.yikuo.youcommuter.core.model.BikeKind
import dev.yikuo.youcommuter.core.model.RouteStation
import java.time.DayOfWeek

@Composable
internal fun RouteEditorScreen(
    editor: RouteEditorState,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onDepartureChanged: (String) -> Unit,
    onEstimatedReturnMinutesChanged: (String) -> Unit,
    onWeekdayToggled: (DayOfWeek) -> Unit,
    onPickStation: (StationTarget) -> Unit,
    onSwapStations: () -> Unit,
    onBikeDisplayChanged: (BikeKind) -> Unit,
    onBikeThresholdChanged: (BikeKind) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val timeFieldMinWidth = 160.dp * density.fontScale
    var thresholdsExpanded by rememberSaveable(editor.id) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().imePadding()) {
        EditorHeader(stringResource(if (editor.id == null) R.string.add_route else R.string.edit_route), onDismiss) {
            AppIconButton(AppIconType.Swap, stringResource(R.string.swap_stations), onSwapStations, enabled = !editor.saving)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = editor.name,
                onValueChange = onNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.route_name)) },
                singleLine = true,
                isError = editor.issue == RouteEditorIssue.MissingName,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                enabled = !editor.saving,
            )
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    StationGroupEditor(
                        title = stringResource(R.string.borrow_group), icon = AppIconType.Bike,
                        primary = editor.borrow, backups = editor.borrowBackups,
                        choosePrimary = { onPickStation(StationTarget.BorrowPrimary) },
                        addBackup = { onPickStation(StationTarget.BorrowBackup) },
                        removeBackup = { onRemoveBackup(BackupGroup.Borrow, it) },
                        enabled = !editor.saving,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StationGroupEditor(
                        title = stringResource(R.string.return_group), icon = AppIconType.Dock,
                        primary = editor.returns, backups = editor.returnBackups,
                        choosePrimary = { onPickStation(StationTarget.ReturnPrimary) },
                        addBackup = { onPickStation(StationTarget.ReturnBackup) },
                        removeBackup = { onRemoveBackup(BackupGroup.Return, it) },
                        enabled = !editor.saving,
                    )
                }
            }
            BikeDisplaySelector(editor.bikeDisplay, onBikeDisplayChanged, enabled = !editor.saving)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val timeFieldWidth = if (maxWidth >= timeFieldMinWidth * 2 + 12.dp) {
                    with(density) { ((constraints.maxWidth - 12.dp.roundToPx()) / 2).toDp() }
                } else maxWidth
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editor.departure, onValueChange = onDepartureChanged,
                        modifier = Modifier.width(timeFieldWidth), label = { Text(stringResource(R.string.departure_time)) },
                        placeholder = { Text("08:30") }, singleLine = true,
                        isError = editor.issue == RouteEditorIssue.InvalidTime,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        enabled = !editor.saving,
                    )
                    OutlinedTextField(
                        value = editor.estimatedReturnMinutes, onValueChange = onEstimatedReturnMinutesChanged,
                        modifier = Modifier.width(timeFieldWidth), label = { Text(stringResource(R.string.return_duration)) },
                        suffix = { Text(stringResource(R.string.minute_unit)) },
                        placeholder = { Text("1–90") }, singleLine = true,
                        isError = editor.issue == RouteEditorIssue.InvalidReturnMinutes,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        enabled = !editor.saving,
                    )
                }
            }
            Column {
                IconText(AppIconType.Calendar, stringResource(R.string.travel_days), style = MaterialTheme.typography.titleSmall)
                // Wrap on narrow screens or with large text instead of hiding the last days off-screen.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val locale = LocalConfiguration.current.locales[0]
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            modifier = Modifier.semantics { contentDescription = day.getDisplayName(TextStyle.FULL, locale) },
                            selected = day in editor.weekdays,
                            onClick = { onWeekdayToggled(day) },
                            enabled = !editor.saving,
                            label = { Text(dayLabel(day)) },
                        )
                    }
                }
            }
            HorizontalDivider()
            ReminderEditor(
                minutesBefore = editor.reminderMinutesBefore, hasScheduledDays = editor.weekdays.isNotEmpty(),
                canPostNotifications = canPostNotifications, notificationPermissionGranted = notificationPermissionGranted,
                onEnabledChanged = onReminderEnabledChanged, onLeadChanged = onReminderLeadChanged,
                onRequestPermission = onRequestNotificationPermission, onOpenSettings = onOpenNotificationSettings,
                enabled = !editor.saving,
            )
            HorizontalDivider()
            Surface(onClick = { thresholdsExpanded = !thresholdsExpanded }, enabled = !editor.saving,
                color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconText(AppIconType.Tune, stringResource(R.string.low_availability), style = MaterialTheme.typography.titleSmall)
                        if (!thresholdsExpanded) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                IconText(if (editor.bikeThreshold == BikeKind.Electric) AppIconType.Bolt else AppIconType.Bike, "≤ ${editor.lowBikeThreshold}",
                                    description = stringResource(R.string.low_bike_threshold, editor.lowBikeThreshold))
                                IconText(AppIconType.Dock, "≤ ${editor.lowDockThreshold}",
                                    description = stringResource(R.string.low_dock_threshold, editor.lowDockThreshold))
                            }
                        }
                    }
                    AppIcon(if (thresholdsExpanded) AppIconType.ExpandLess else AppIconType.ExpandMore,
                        contentDescription = stringResource(if (thresholdsExpanded) R.string.collapse else R.string.adjust))
                }
            }
            if (thresholdsExpanded) {
                if (editor.bikeDisplay == BikeKind.All) {
                    BikeKindSelector(editor.bikeThreshold, onBikeThresholdChanged, threshold = true, enabled = !editor.saving)
                }
                ThresholdStepper(if (editor.bikeThreshold == BikeKind.Electric) AppIconType.Bolt else AppIconType.Bike, stringResource(R.string.low_bike_threshold, editor.lowBikeThreshold),
                    editor.lowBikeThreshold, onLowBikeThresholdChanged, !editor.saving)
                ThresholdStepper(AppIconType.Dock, stringResource(R.string.low_dock_threshold, editor.lowDockThreshold),
                    editor.lowDockThreshold, onLowDockThresholdChanged, !editor.saving)
            }
        }
        Surface(shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                editor.issue?.let {
                    Text(editorIssue(it), Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { focus.clearFocus(); onSave() }, enabled = !editor.saving, modifier = Modifier.fillMaxWidth()) {
                    if (editor.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else {
                        AppIcon(AppIconType.Check)
                        Text(stringResource(R.string.save), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditorHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIconType.Back, stringResource(R.string.back), onBack)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        actions()
    }
}

@Composable
private fun StationGroupEditor(
    title: String, icon: AppIconType, primary: RouteStation?, backups: List<RouteStation>,
    choosePrimary: () -> Unit, addBackup: () -> Unit, removeBackup: (RouteStation) -> Unit, enabled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = choosePrimary, enabled = enabled, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
                Text(primary?.name ?: stringResource(R.string.choose_primary), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                AppIcon(AppIconType.ChevronRight)
            }
        }
        if (backups.size < 5) {
            val backupLabel = stringResource(R.string.add_backup)
            AppIconButton(AppIconType.Add, "$title: $backupLabel", addBackup, enabled = enabled)
        }
    }
    backups.forEach { station ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconText(AppIconType.Backup, station.name, Modifier.weight(1f))
            AppIconButton(AppIconType.Close, stringResource(R.string.remove_station, station.name),
                { removeBackup(station) }, enabled = enabled)
        }
    }
    if (backups.size >= 5) {
        Text(stringResource(R.string.backup_limit), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ThresholdStepper(icon: AppIconType, label: String, value: Int, onValueChanged: (Int) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconText(icon, "≤ $value", Modifier.weight(1f), description = label)
        AppIconButton(AppIconType.Remove, stringResource(R.string.decrease_threshold, label),
            { onValueChanged(value - 1) }, enabled = enabled && value > AvailabilityThresholdRange.first)
        AppIconButton(AppIconType.Add, stringResource(R.string.increase_threshold, label),
            { onValueChanged(value + 1) }, enabled = enabled && value < AvailabilityThresholdRange.last)
    }
}

@Composable
private fun ReminderEditor(
    minutesBefore: Int?, hasScheduledDays: Boolean, canPostNotifications: Boolean,
    notificationPermissionGranted: Boolean, onEnabledChanged: (Boolean) -> Unit,
    onLeadChanged: (Int) -> Unit, onRequestPermission: () -> Unit, onOpenSettings: () -> Unit, enabled: Boolean,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconText(AppIconType.Bell, stringResource(R.string.departure_reminder), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = minutesBefore != null, onCheckedChange = onEnabledChanged, enabled = enabled)
        }
        if (minutesBefore != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 5, 10, 15, 30).forEach { minutes ->
                    FilterChip(selected = minutesBefore == minutes, onClick = { onLeadChanged(minutes) }, enabled = enabled,
                        label = { Text(if (minutes == 0) stringResource(R.string.at_departure) else stringResource(R.string.minutes_short, minutes)) })
                }
            }
            if (!hasScheduledDays) Text(stringResource(R.string.reminder_needs_day), color = MaterialTheme.colorScheme.error)
            if (!canPostNotifications) {
                Text(stringResource(if (notificationPermissionGranted) R.string.notifications_disabled else R.string.notification_permission_needed),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = if (notificationPermissionGranted) onOpenSettings else onRequestPermission, enabled = enabled) {
                    AppIcon(AppIconType.Bell)
                    Text(stringResource(if (notificationPermissionGranted) R.string.open_settings else R.string.allow_notifications),
                        Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun dayLabel(day: DayOfWeek): String = stringResource(when (day) {
    DayOfWeek.MONDAY -> R.string.day_mon
    DayOfWeek.TUESDAY -> R.string.day_tue
    DayOfWeek.WEDNESDAY -> R.string.day_wed
    DayOfWeek.THURSDAY -> R.string.day_thu
    DayOfWeek.FRIDAY -> R.string.day_fri
    DayOfWeek.SATURDAY -> R.string.day_sat
    DayOfWeek.SUNDAY -> R.string.day_sun
})

@Composable
private fun editorIssue(issue: RouteEditorIssue): String = stringResource(when (issue) {
    RouteEditorIssue.MissingName -> R.string.error_route_name
    RouteEditorIssue.MissingStation -> R.string.error_route_station
    RouteEditorIssue.InvalidTime -> R.string.error_time
    RouteEditorIssue.InvalidReturnMinutes -> R.string.error_return_minutes
    RouteEditorIssue.Storage -> R.string.error_storage
})

@Composable
private fun BikeKindSelector(kind: BikeKind, onSelected: (BikeKind) -> Unit, threshold: Boolean, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val field = stringResource(if (threshold) R.string.bike_threshold else R.string.bike_display)
    val label = bikeKindLabel(kind, threshold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(field, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Box {
            Surface(onClick = { expanded = true }, enabled = enabled, shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.semantics { contentDescription = "$field: $label" }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BikeKindIcons(kind)
                    AppIcon(AppIconType.ExpandMore)
                }
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                BikeKind.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(bikeKindLabel(value, threshold)) },
                        leadingIcon = { BikeKindIcons(value) },
                        trailingIcon = { if (kind == value) AppIcon(AppIconType.Check) },
                        onClick = { expanded = false; onSelected(value) })
                }
            }
        }
    }
}

@Composable
private fun BikeKindIcons(kind: BikeKind) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kind != BikeKind.Electric) AppIcon(AppIconType.Bike, Modifier.size(20.dp))
        if (kind == BikeKind.All) AppIcon(AppIconType.Add, Modifier.size(12.dp))
        if (kind != BikeKind.Regular) AppIcon(AppIconType.Bolt, Modifier.size(20.dp))
    }
}

@Composable
private fun bikeKindLabel(kind: BikeKind, threshold: Boolean) = stringResource(when (kind) {
    BikeKind.All -> if (threshold) R.string.bike_total else R.string.bike_all
    BikeKind.Regular -> R.string.bike_regular
    BikeKind.Electric -> R.string.bike_electric
})

@Composable
private fun BikeDisplaySelector(kind: BikeKind, onSelected: (BikeKind) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bike_display), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        for (value in listOf(BikeKind.Regular, BikeKind.Electric)) {
            val selected = kind == BikeKind.All || kind == value
            val label = bikeKindLabel(value, false)
            FilterChip(selected = selected, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = label },
                onClick = {
                    if (!selected) onSelected(BikeKind.All)
                    else if (kind == BikeKind.All) onSelected(if (value == BikeKind.Regular) BikeKind.Electric else BikeKind.Regular)
                },
                leadingIcon = { if (selected) AppIcon(AppIconType.Check, Modifier.size(16.dp)) },
                label = { AppIcon(if (value == BikeKind.Regular) AppIconType.Bike else AppIconType.Bolt, Modifier.size(20.dp)) })
        }
    }
}
