package app.batterycommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.PowerFlow
import app.batterycommander.domain.signalBars
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 31, so Material You dynamic color is always available.
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Current time, ticking every 15 s, so "5m ago" labels stay fresh. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun SignalBars(rssiDbm: Int?, modifier: Modifier = Modifier) {
    val bars = signalBars(rssiDbm)
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            Box(
                Modifier.width(4.dp).height((4 + 3 * i).dp)
                    .background(if (i <= bars) on else off, RoundedCornerShape(1.dp)),
            )
        }
        Text(
            rssiDbm?.let { " $it dBm" } ?: " —",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun volts(mv: Int): String = String.format(Locale.US, "%.2f V", mv / 1000.0)
fun cellVolts(mv: Int): String = String.format(Locale.US, "%.3f V", mv / 1000.0)
fun amps(ma: Int): String = String.format(Locale.US, "%+.1f A", ma / 1000.0)
fun watts(mw: Long): String = String.format(Locale.US, "%+.0f W", mw / 1000.0)
fun ampHours(mah: Int): String = String.format(Locale.US, "%.1f Ah", mah / 1000.0)
fun celsius(deci: Int): String = String.format(Locale.US, "%.1f °C", deci / 10.0)

fun flowLabel(r: BatteryReading): String = when {
    !r.chargeFetOn && !r.dischargeFetOn -> "Charge & discharge off"
    !r.chargeFetOn -> "Charge off"
    !r.dischargeFetOn -> "Discharge off"
    else -> when (r.flow) {
        PowerFlow.CHARGING -> "Charging"
        PowerFlow.DISCHARGING -> "Discharging"
        PowerFlow.IDLE -> "Idle"
    }
}

/** Protection names like CELL_OVERVOLTAGE → "Cell overvoltage". */
fun protectionLabel(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
