package dev.yikuo.youcommuter.feature.commute

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.core.domain.all
import dev.yikuo.youcommuter.core.map.StationMapMode
import dev.yikuo.youcommuter.core.ui.AutoRefreshEffect
import dev.yikuo.youcommuter.core.map.MapMetric
import dev.yikuo.youcommuter.core.map.StationMap
import dev.yikuo.youcommuter.core.map.mapPoint
import dev.yikuo.youcommuter.core.model.CommutePhase
import dev.yikuo.youcommuter.core.model.CommuteRoute
import dev.yikuo.youcommuter.core.model.CommuteSession
import dev.yikuo.youcommuter.core.model.RouteStation
import dev.yikuo.youcommuter.core.model.isTerminal
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.IconText
import java.util.UUID

@Composable
fun CommuteRoute(
    routeRepository: RouteRepository,
    sessionRepository: CommuteSessionRepository,
    stationRepository: StationRepository,
    onManageRoutes: () -> Unit,
    modifier: Modifier = Modifier,
    onBorrowObservation: ((UUID?) -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val model: CommuteViewModel = viewModel(factory = viewModelFactory {
        initializer { CommuteViewModel(routeRepository, sessionRepository, stationRepository) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    AutoRefreshEffect(model::autoRefresh)
    CommuteScreen(
        state = state,
        onStart = model::start,
        onBorrowed = model::confirmBorrowed,
        onReturned = model::confirmReturned,
        onCancel = model::cancel,
        onBorrowStationSelected = model::selectBorrowStation,
        onReturnStationSelected = model::selectReturnStation,
        onAvailabilityRefresh = model::refreshAvailability,
        onDone = model::dismissFinished,
        onManageRoutes = onManageRoutes,
        onIssueDismissed = model::clearIssue,
        modifier = modifier,
        onBorrowObservation = onBorrowObservation,
        onSettings = onSettings,
    )
}

@Composable
fun CommuteScreen(
    state: CommuteUiState,
    onStart: (CommuteRoute) -> Unit,
    onBorrowed: () -> Unit,
    onReturned: () -> Unit,
    onCancel: () -> Unit,
    onBorrowStationSelected: (RouteStation) -> Unit,
    onReturnStationSelected: (RouteStation) -> Unit,
    onAvailabilityRefresh: () -> Unit,
    onDone: () -> Unit,
    onManageRoutes: () -> Unit,
    onIssueDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    onBorrowObservation: ((UUID?) -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.commute_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (onSettings != null) AppIconButton(AppIconType.Settings, stringResource(R.string.prediction_settings), onSettings)
            if (state.session == null) {
                AppIconButton(AppIconType.Route, stringResource(R.string.manage_routes), onManageRoutes)
            }
            if (onBorrowObservation != null) {
                AppIconButton(AppIconType.Tune, stringResource(R.string.borrow_observation),
                    onClick = { onBorrowObservation(state.session?.id) })
            }
        }
        state.issue?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(AppIconType.Warning, tint = MaterialTheme.colorScheme.error)
                    Text(issueText(it), Modifier.weight(1f).padding(start = 8.dp), color = MaterialTheme.colorScheme.error)
                    AppIconButton(AppIconType.Close, stringResource(R.string.dismiss), onIssueDismissed)
                }
            }
        }
        val session = state.session
        if (session == null) {
            IdleContent(state.routes, state.busy, onStart, onManageRoutes)
        } else {
            ActiveSessionCard(state, session, onBorrowed, onReturned, onCancel, onDone,
                onBorrowStationSelected, onReturnStationSelected, onAvailabilityRefresh)
        }
    }
}

@Composable
private fun IdleContent(routes: List<CommuteRoute>, busy: Boolean, onStart: (CommuteRoute) -> Unit, onManageRoutes: () -> Unit) {
    if (routes.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.no_route_title), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onManageRoutes) {
                    AppIcon(AppIconType.Add)
                    Text(stringResource(R.string.create_route), Modifier.padding(start = 8.dp))
                }
            }
        }
    } else {
        routes.forEach { route ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(route.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { onStart(route) }, enabled = !busy) {
                            AppIcon(AppIconType.Play)
                            Text(stringResource(R.string.start_commute), Modifier.padding(start = 4.dp))
                        }
                    }
                    IconText(AppIconType.Bike, route.borrow.primary.name)
                    IconText(AppIconType.Dock, route.returns.primary.name)
                    IconText(AppIconType.Clock, route.departure.toString(),
                        description = stringResource(R.string.departure, route.departure.toString()))
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    state: CommuteUiState,
    session: CommuteSession,
    onBorrowed: () -> Unit,
    onReturned: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onBorrowStationSelected: (RouteStation) -> Unit,
    onReturnStationSelected: (RouteStation) -> Unit,
    onAvailabilityRefresh: () -> Unit,
) {
    val busy = state.busy
    val borrowing = session.phase in setOf(CommutePhase.Preparing, CommutePhase.WalkingToBorrow)
    val riding = session.phase in setOf(CommutePhase.Riding, CommutePhase.NearReturn)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(session.routeSnapshot.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconText(phaseIcon(session.phase), phaseTitle(session.phase), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                if (borrowing || riding) {
                    AppIconButton(AppIconType.Refresh, stringResource(R.string.refresh), onAvailabilityRefresh,
                        enabled = !state.refreshingAvailability && !state.borrowAvailability.loading && !state.returnAvailability.loading)
                }
            }
            if (borrowing || riding) {
                CommuteMap(state, session, borrowing, !busy, onBorrowStationSelected, onReturnStationSelected)
            }
            if (borrowing) {
                CommuteStationGroup(session.routeSnapshot.borrow.all, session.selectedBorrow, state,
                    session.routeSnapshot, borrowing = true, enabled = !busy, onSelected = onBorrowStationSelected)
            }
            if (borrowing || riding) {
                CommuteStationGroup(session.routeSnapshot.returns.all, session.selectedReturn, state,
                    session.routeSnapshot, borrowing = false, enabled = !busy, onSelected = onReturnStationSelected)
            } else {
                IconText(AppIconType.Bike, (session.actualBorrow ?: session.selectedBorrow).name)
                IconText(AppIconType.Dock, (session.actualReturn ?: session.selectedReturn).name)
            }
            when (session.phase) {
                CommutePhase.Preparing, CommutePhase.WalkingToBorrow -> Button(
                    onClick = onBorrowed, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) {
                    AppIcon(AppIconType.Bike)
                    Text(stringResource(R.string.confirm_borrowed), Modifier.padding(start = 8.dp))
                }
                CommutePhase.Riding, CommutePhase.NearReturn -> Button(
                    onClick = onReturned, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                ) {
                    AppIcon(AppIconType.Dock)
                    Text(stringResource(R.string.confirm_returned), Modifier.padding(start = 8.dp))
                }
                else -> Unit
            }
            if (session.phase.isTerminal) {
                Button(onClick = onDone, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(AppIconType.Check)
                    Text(stringResource(R.string.done), Modifier.padding(start = 8.dp))
                }
            } else {
                OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(AppIconType.Close)
                    Text(stringResource(R.string.cancel_commute), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CommuteMap(
    state: CommuteUiState, session: CommuteSession, borrowing: Boolean, enabled: Boolean,
    onBorrowStationSelected: (RouteStation) -> Unit, onReturnStationSelected: (RouteStation) -> Unit,
) {
    val stations = state.mapStations.associateBy { it.id }
    val refs = if (borrowing) session.routeSnapshot.borrow.all else session.routeSnapshot.returns.all
    val selected = if (borrowing) session.selectedBorrow else session.selectedReturn
    val metric = if (borrowing) MapMetric.Bikes else MapMetric.Docks
    val points = refs.mapNotNull { ref -> stations[ref.id]?.let { station ->
        val point = station.mapPoint(metric, ref.id == selected.id, session.routeSnapshot.bikeDisplay)
        if (state.stationForCounts(ref.id) == null) point.copy(inventory = point.inventory?.copy(count = null)) else point
    } }
    val modifier = Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium)
    if (points.isEmpty()) Box(modifier, contentAlignment = Alignment.Center) {
        if (state.mapLoading) CircularProgressIndicator(Modifier.size(24.dp))
        else AppIcon(AppIconType.Location)
    } else StationMap(points, onStationSelected = { point ->
        if (enabled) refs.firstOrNull { it.id.value == point.stationId }?.let {
            if (borrowing) onBorrowStationSelected(it) else onReturnStationSelected(it)
        }
    }, modifier = modifier, cameraKey = "${session.id}:$borrowing", mode = StationMapMode.Overview)
}

private fun phaseIcon(phase: CommutePhase): AppIconType = when (phase) {
    CommutePhase.Preparing, CommutePhase.WalkingToBorrow -> AppIconType.Walk
    CommutePhase.Riding -> AppIconType.Bike
    CommutePhase.NearReturn -> AppIconType.Dock
    CommutePhase.Completed -> AppIconType.Check
    CommutePhase.Cancelled, CommutePhase.Skipped -> AppIconType.Close
    CommutePhase.NeedsReview -> AppIconType.Warning
}

@Composable
private fun phaseTitle(phase: CommutePhase): String = stringResource(when (phase) {
    CommutePhase.Preparing -> R.string.phase_preparing
    CommutePhase.WalkingToBorrow -> R.string.phase_walking
    CommutePhase.Riding -> R.string.phase_riding
    CommutePhase.NearReturn -> R.string.phase_near_return
    CommutePhase.Completed -> R.string.phase_completed
    CommutePhase.Skipped -> R.string.phase_skipped
    CommutePhase.Cancelled -> R.string.phase_cancelled
    CommutePhase.NeedsReview -> R.string.phase_review
})

@Composable
private fun issueText(issue: CommuteIssue): String = stringResource(when (issue) {
    CommuteIssue.InvalidAction -> R.string.invalid_action
    CommuteIssue.Storage -> R.string.storage_error
})
