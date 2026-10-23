package dev.yikuo.youcommuter.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.yikuo.youcommuter.core.ui.AppIcon
import dev.yikuo.youcommuter.core.ui.AppIconType
import dev.yikuo.youcommuter.core.ui.pageTransition
import dev.yikuo.youcommuter.R
import dev.yikuo.youcommuter.BuildConfig
import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.domain.CommuteSessionRepository
import dev.yikuo.youcommuter.core.domain.CoverageRepository
import dev.yikuo.youcommuter.core.domain.RouteRepository
import dev.yikuo.youcommuter.core.domain.StationRepository
import dev.yikuo.youcommuter.feature.commute.CommuteRoute
import dev.yikuo.youcommuter.feature.stations.StationBrowserRoute
import dev.yikuo.youcommuter.feature.routes.RoutesRoute
import java.util.UUID

private enum class Destination(val label: Int, val icon: AppIconType) {
    Commute(R.string.tab_commute, AppIconType.Bike),
    Stations(R.string.tab_stations, AppIconType.Location),
    Routes(R.string.tab_routes, AppIconType.Route),
}

@Composable
fun YouCommuterApp(
    coverageRepository: CoverageRepository,
    stationRepository: StationRepository,
    routeRepository: RouteRepository,
    sessionRepository: CommuteSessionRepository,
    onBorrowObservation: ((UUID?) -> Unit)? = null,
    predictionSettings: PredictionSettings,
    testPrediction: suspend (PredictionServiceConfiguration) -> StationResult<Unit>,
    onPredictionChanged: suspend () -> Unit,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(Destination.Commute) }
    var routeEditorOpen by remember { mutableStateOf(false) }
    val destinationState = rememberSaveableStateHolder()
    AnimatedContent(targetState = settingsOpen, modifier = Modifier.fillMaxSize(),
        transitionSpec = { pageTransition(forward = targetState) }, label = "settings") { showSettings ->
        if (showSettings) {
            PredictionSettingsScreen(predictionSettings, BuildConfig.DEBUG, testPrediction, onPredictionChanged,
                onBack = { settingsOpen = false }, active = settingsOpen)
        } else Scaffold(bottomBar = {
            AnimatedVisibility(visible = selectedTab != Destination.Routes || !routeEditorOpen,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(220))) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = selectedTab == destination,
                            onClick = { selectedTab = destination },
                            icon = { AppIcon(destination.icon) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        }) { padding ->
            val contentModifier = Modifier.padding(padding).consumeWindowInsets(padding)
            Crossfade(targetState = selectedTab, modifier = contentModifier.fillMaxSize(),
                animationSpec = tween(180), label = "tabs") { destination ->
                destinationState.SaveableStateProvider(destination.name) {
                    when (destination) {
                        Destination.Commute -> CommuteRoute(
                            routeRepository = routeRepository,
                            sessionRepository = sessionRepository,
                            stationRepository = stationRepository,
                            onManageRoutes = { selectedTab = Destination.Routes },
                            modifier = Modifier.fillMaxSize(),
                            onBorrowObservation = onBorrowObservation,
                            onSettings = { settingsOpen = true },
                        )
                        Destination.Stations -> StationBrowserRoute(coverageRepository, stationRepository, Modifier.fillMaxSize())
                        Destination.Routes -> RoutesRoute(
                            coverageRepository = coverageRepository,
                            stationRepository = stationRepository,
                            routeRepository = routeRepository,
                            onEditorVisibilityChanged = { routeEditorOpen = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
