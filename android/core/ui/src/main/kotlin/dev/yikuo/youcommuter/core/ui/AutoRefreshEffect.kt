package dev.yikuo.youcommuter.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Only the visible, started screen polls. View models skip ticks while a read is in flight. */
@Composable
fun AutoRefreshEffect(onRefresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(3_000)
                refresh()
            }
        }
    }
}
