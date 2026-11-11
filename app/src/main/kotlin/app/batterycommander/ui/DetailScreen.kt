package app.batterycommander.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batterycommander.data.BatteryRepository
import app.batterycommander.data.RefreshStatus
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.PowerFlow
import app.batterycommander.domain.SocThresholds
import app.batterycommander.domain.TimeEstimate
import app.batterycommander.domain.basis
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(repo: BatteryRepository, mac: String, onBack: () -> Unit) {
    val batteries by repo.batteries.collectAsStateWithLifecycle()
    val b = batteries.firstOrNull { it.favorite.mac == mac }
    if (b == null) {
        // Removed while open; navigate away outside composition.
        LaunchedEffect(mac) { onBack() }
        return
    }
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    val refreshing = b.status is RefreshStatus.Refreshing
    var editingThresholds by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(b.favorite.nickname) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { scope.launch { repo.refresh(mac) } }) { Icon(Icons.Filled.Refresh, "Refresh now") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { StatusLine(b, now) }
                    SignalBars(b.reading?.rssiDbm)
                }
                (b.status as? RefreshStatus.Failed)?.let {
                    Text("Last refresh failed: ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            val r = b.reading
            if (r == null) {
                item { Text("No data yet. Tap refresh to read this battery.") }
            } else {
                item { Summary(r) }
                item { Estimate(b.estimate, b.favorite.thresholds, now) { editingThresholds = true } }
                item { Cells(r) }
                item { Status(r) }
                item { Lifetime(r, mac) }
                item {
                    Section("Controls") {
                        Text(
                            "Charge/discharge switches and charge limits come in a later milestone (docs/product/features/control.md).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (editingThresholds) {
        ThresholdsDialog(
            title = "Thresholds for ${b.favorite.nickname}",
            initial = b.favorite.thresholds,
            onDismiss = { editingThresholds = false },
            onSave = { repo.setThresholds(mac, it); editingThresholds = false },
        )
    }
}

@Composable
private fun Estimate(e: TimeEstimate?, t: SocThresholds, now: Long, onEdit: () -> Unit) = Section("Time estimate") {
    if (e == null || e.flow == PowerFlow.IDLE) {
        Text("Idle: no estimate while no current is flowing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        EstimateLine(e, now)
        Field("Average current", amps(e.rateMa))
        Field("Rate", e.basis())
        if (!e.confident) {
            Text(
                "Rough: " + if (e.spanMs == 0L) "not enough history yet." else "the load or charger changed recently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(thresholdsLabel(t), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onEdit) { Text("Thresholds…") }
    }
}

@Composable
private fun Summary(r: BatteryReading) = Section("Now") {
    Text("${r.stateOfChargePct}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Field("Voltage", volts(r.packVoltageMv))
    Field("Current", amps(r.currentMa))
    Field("Power", watts(r.powerMw))
    Field("Remaining", "${ampHours(r.remainingCapacityMah)} of ${ampHours(r.fullChargeCapacityMah ?: r.nominalCapacityMah)}")
    r.temperaturesDeciC.forEachIndexed { i, t ->
        Field(if (r.temperaturesDeciC.size == 1) "Temperature" else "Temperature ${i + 1}", celsius(t))
    }
}

@Composable
private fun Cells(r: BatteryReading) = Section("Cells") {
    val max = r.cellVoltagesMv.maxOrNull()
    val min = r.cellVoltagesMv.minOrNull()
    r.cellVoltagesMv.forEachIndexed { i, mv ->
        val balancing = r.balancingMask and (1L shl i) != 0L
        val color = when {
            max == min -> Color.Unspecified
            mv == max -> MaterialTheme.colorScheme.primary
            mv == min -> MaterialTheme.colorScheme.tertiary
            else -> Color.Unspecified
        }
        Field("Cell ${i + 1}" + if (balancing) " (balancing)" else "", cellVolts(mv), valueColor = color)
    }
    r.cellDeltaMv?.let { Field("Spread (max − min)", "$it mV") }
}

@Composable
private fun Status(r: BatteryReading) = Section("Status") {
    Field("Charging switch", if (r.chargeFetOn) "On" else "Off")
    Field("Discharge switch", if (r.dischargeFetOn) "On" else "Off")
    if (r.protections.isEmpty()) {
        Field("Protections", "None active")
    } else {
        r.protections.forEach { Field("Protection", protectionLabel(it), valueColor = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Lifetime(r: BatteryReading, mac: String) = Section("Battery") {
    Field("Cycles", r.cycles.toString())
    Field("Rated capacity", ampHours(r.nominalCapacityMah))
    r.fullChargeCapacityMah?.let { Field("Full-charge capacity", ampHours(it)) }
    Field("Manufactured", r.productionDate)
    Field("Model", r.model)
    Field("BMS firmware", r.firmwareVersion)
    Field("Bluetooth address", mac)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = valueColor, fontWeight = FontWeight.Medium)
    }
}
