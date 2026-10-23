package dev.yikuo.youcommuter.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class AppIconType(internal val resource: Int) {
    Settings(R.drawable.ic_settings),
    Bike(R.drawable.ic_bike),
    Dock(R.drawable.ic_dock),
    Route(R.drawable.ic_route),
    Location(R.drawable.ic_location),
    Map(R.drawable.ic_map),
    List(R.drawable.ic_list),
    FitMap(R.drawable.ic_fit_map),
    Add(R.drawable.ic_add),
    Remove(R.drawable.ic_remove),
    Edit(R.drawable.ic_edit),
    Copy(R.drawable.ic_copy),
    Swap(R.drawable.ic_swap),
    MyLocation(R.drawable.ic_my_location),
    Delete(R.drawable.ic_delete),
    Close(R.drawable.ic_close),
    Check(R.drawable.ic_check),
    Search(R.drawable.ic_search),
    Refresh(R.drawable.ic_refresh),
    Clock(R.drawable.ic_clock),
    Timer(R.drawable.ic_timer),
    Bell(R.drawable.ic_bell),
    Tune(R.drawable.ic_tune),
    Calendar(R.drawable.ic_calendar),
    ChevronRight(R.drawable.ic_chevron_right),
    ExpandMore(R.drawable.ic_expand_more),
    ExpandLess(R.drawable.ic_expand_less),
    Back(R.drawable.ic_back),
    Backup(R.drawable.ic_backup),
    Play(R.drawable.ic_play),
    Walk(R.drawable.ic_walk),
    Warning(R.drawable.ic_warning),
    Bolt(R.drawable.ic_bolt);
}

@Composable
fun AppIcon(
    icon: AppIconType,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon.resource), contentDescription, modifier.size(24.dp), tint)
}

/** A labeled touch target, with its name available to TalkBack and on long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconButton(
    icon: AppIconType,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            AppIcon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}

@Composable
fun IconText(
    icon: AppIconType,
    text: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val semantics = if (description == null) Modifier else Modifier.clearAndSetSemantics { contentDescription = description }
    Row(modifier.then(semantics), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(icon, modifier = Modifier.size(20.dp))
        Text(text, style = style)
    }
}

@Composable
fun AvailabilityCount(
    icon: AppIconType,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
) {
    Row(modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(icon, tint = MaterialTheme.colorScheme.primary)
        Text(value, style = style, fontWeight = FontWeight.SemiBold)
    }
}
