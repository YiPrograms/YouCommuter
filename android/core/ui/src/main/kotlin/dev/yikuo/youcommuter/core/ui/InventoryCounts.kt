package dev.yikuo.youcommuter.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Bike subtypes share one group; return docks remain separate. */
@Composable
fun InventoryCounts(regular: Int?, electric: Int?, docks: Int?, modifier: Modifier = Modifier) {
    val unknown = stringResource(R.string.inventory_unknown)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BikeCounts(regular, electric, Modifier.weight(1f))
        AvailabilityCount(AppIconType.Dock, docks?.toString() ?: "—",
            stringResource(R.string.inventory_docks, docks?.toString() ?: unknown))
    }
}

@Composable
fun BikeCounts(regular: Int?, electric: Int?, modifier: Modifier = Modifier) {
    val unknown = stringResource(R.string.inventory_unknown)
    Surface(modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        FlowRow(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AvailabilityCount(AppIconType.Bike, regular?.toString() ?: "—",
                stringResource(R.string.inventory_regular, regular?.toString() ?: unknown))
            AvailabilityCount(AppIconType.Bolt, electric?.toString() ?: "—",
                stringResource(R.string.inventory_electric, electric?.toString() ?: unknown))
        }
    }
}
