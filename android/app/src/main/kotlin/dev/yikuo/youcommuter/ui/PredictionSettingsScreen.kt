package dev.yikuo.youcommuter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.yikuo.youcommuter.R
import dev.yikuo.youcommuter.core.data.validatePredictionConfiguration
import dev.yikuo.youcommuter.core.domain.PredictionSettings
import dev.yikuo.youcommuter.core.model.PredictionServiceConfiguration
import dev.yikuo.youcommuter.core.model.StationResult
import dev.yikuo.youcommuter.core.ui.AppIconButton
import dev.yikuo.youcommuter.core.ui.AppIconType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class PredictionSettingsViewModel(
    private val settings: PredictionSettings,
    private val allowLocalHttp: Boolean,
    private val test: suspend (PredictionServiceConfiguration) -> StationResult<Unit>,
    private val onChanged: suspend () -> Unit,
) : ViewModel() {
    var draft by mutableStateOf(settings.load()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<Int?>(null); private set
    val dirty: Boolean get() = draft != settings.load()
    fun endpoint(value: String) { draft = draft.copy(endpoint = value); message = null }
    fun key(value: String) { draft = draft.copy(apiKey = value); message = null }
    fun discard() { draft = settings.load(); message = null }
    fun submit(checkOnly: Boolean) {
        if (busy) return
        val validated = try { validatePredictionConfiguration(draft, allowLocalHttp) } catch (_: IllegalArgumentException) {
            message = R.string.prediction_invalid_url; return
        }
        if (checkOnly && validated.endpoint.isBlank()) { message = R.string.prediction_enter_endpoint; return }
        busy = true; message = null
        viewModelScope.launch {
            try {
                if (checkOnly) {
                    message = when (test(validated)) {
                        is StationResult.Success -> R.string.prediction_connected
                        is StationResult.Failure -> R.string.prediction_connection_failed
                    }
                } else {
                    val changed = validated != settings.load()
                    settings.save(validated); draft = validated
                    if (changed) onChanged()
                    message = if (validated.endpoint.isEmpty()) R.string.prediction_disabled else R.string.prediction_saved
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { message = R.string.prediction_save_failed
            } finally { busy = false }
        }
    }
}

@Composable
internal fun PredictionSettingsScreen(settings: PredictionSettings, allowLocalHttp: Boolean,
    test: suspend (PredictionServiceConfiguration) -> StationResult<Unit>, onChanged: suspend () -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    val model: PredictionSettingsViewModel = viewModel(factory = viewModelFactory {
        initializer { PredictionSettingsViewModel(settings, allowLocalHttp, test, onChanged) }
    })
    var confirmDiscard by remember { mutableStateOf(false) }
    val back = { if (model.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = active, onBack = back)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            AppIconButton(AppIconType.Back, stringResource(R.string.settings_back), back)
            Text(stringResource(R.string.prediction_settings), Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = model.draft.endpoint, onValueChange = model::endpoint,
                label = { Text(stringResource(R.string.prediction_endpoint)) },
                placeholder = { Text("https://…/v1/forecasts:batch") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !model.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(value = model.draft.apiKey, onValueChange = model::key,
                label = { Text(stringResource(R.string.prediction_api_key)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !model.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { model.submit(true) }, modifier = Modifier.weight(1f),
                    enabled = !model.busy && model.draft.endpoint.isNotBlank()) {
                    Text(stringResource(R.string.prediction_test))
                }
                Button(onClick = { model.submit(false) }, enabled = !model.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.prediction_save))
                }
            }
            model.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.settings_discard_title)) },
        confirmButton = { TextButton(onClick = { model.discard(); confirmDiscard = false; onBack() }) { Text(stringResource(R.string.settings_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.settings_keep_editing)) } })
}
