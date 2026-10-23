package dev.yikuo.youcommuter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.yikuo.youcommuter.core.platform.DepartureReminderWorker
import dev.yikuo.youcommuter.ui.YouCommuterApp
import dev.yikuo.youcommuter.ui.YouCommuterTheme
import dev.yikuo.youcommuter.ui.BorrowDetectionEffect
import dev.yikuo.youcommuter.observation.borrowObservationLauncher

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YouCommuterApplication
        val container = app.container
        setContent {
            BorrowDetectionEffect(container.sessions)
            YouCommuterTheme {
                YouCommuterApp(container.coverage, container.stations, container.routes, container.sessions,
                    onBorrowObservation = borrowObservationLauncher(this),
                    predictionSettings = app.predictionSettings,
                    testPrediction = container.testPrediction,
                    onPredictionChanged = { DepartureReminderWorker.clearPosted(app) })
            }
        }
    }
}
