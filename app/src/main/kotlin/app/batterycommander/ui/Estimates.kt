package app.batterycommander.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.batterycommander.domain.SocThresholds
import app.batterycommander.domain.TimeEstimate
import app.batterycommander.domain.describe
import app.batterycommander.domain.socThresholds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "around 9:40 PM", with the weekday when it isn't today. */
fun clockTime(ms: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val time = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val sameDay = t.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return "around " + if (sameDay) time else t.format(DateTimeFormatter.ofPattern("EEE")) + " " + time
}

/** "⏱ Down to 20% in 6 h 20 min · around 9:40 PM", greyed and marked "rough" when not confident. Nothing when idle. */
@Composable
fun EstimateLine(e: TimeEstimate?, now: Long) {
    val text = e?.describe(now) ?: return
    val parts = buildList {
        add(text)
        val eta = e.etaMs
        if (e.durationMs != 0L && eta != null && eta > now) add(clockTime(eta, now))
        if (!e.confident) add("rough")
    }
    Text(
        "⏱ " + parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (e.confident) FontWeight.Medium else FontWeight.Normal,
        color = if (e.confident) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun thresholdsLabel(t: SocThresholds) = "Discharge to ${t.low}% · charge to ${t.high}%"

/** Edits the levels a time estimate counts toward. Blank fields mean the default (0% and 100%). */
@Composable
fun ThresholdsDialog(title: String, initial: SocThresholds, onDismiss: () -> Unit, onSave: (SocThresholds) -> Unit) {
    var low by remember { mutableStateOf(initial.dischargeToPct?.toString().orEmpty()) }
    var high by remember { mutableStateOf(initial.chargeToPct?.toString().orEmpty()) }
    val parsed = runCatching { socThresholds(low.trim().ifEmpty { null }?.toInt(), high.trim().ifEmpty { null }?.toInt()) }
    val digits = { s: String -> s.length <= 3 && s.all(Char::isDigit) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        low, { if (digits(it)) low = it }, Modifier.weight(1f),
                        label = { Text("Discharge to %") }, placeholder = { Text("0") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        high, { if (digits(it)) high = it }, Modifier.weight(1f),
                        label = { Text("Charge to %") }, placeholder = { Text("100") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(
                    parsed.exceptionOrNull()?.message
                        ?: "Time estimates count toward these levels. Blank means 0% and 100%. Nothing is sent to the battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(parsed.getOrThrow()) }, enabled = parsed.isSuccess) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
