package app.batterycommander.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batterycommander.ble.ScanHit
import app.batterycommander.data.BatteryRepository
import app.batterycommander.data.BatteryUi
import app.batterycommander.data.RefreshStatus
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.formatAge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(repo: BatteryRepository, onOpen: (String) -> Unit) {
    val batteries by repo.batteries.collectAsStateWithLifecycle()
    val discovered by repo.discovered.collectAsStateWithLifecycle()
    val searching by repo.searching.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()

    var adding by remember { mutableStateOf<ScanHit?>(null) }
    var renaming by remember { mutableStateOf<BatteryUi?>(null) }
    var identifying by remember { mutableStateOf<ScanHit?>(null) }
    var identifyResult by remember { mutableStateOf<Result<BatteryReading>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batteries") },
                actions = {
                    IconButton(onClick = repo::refreshAll) { Icon(Icons.Filled.Refresh, "Refresh all") }
                    IconButton(onClick = { repo.search() }, enabled = !searching) { Icon(Icons.Filled.Search, "Search") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (batteries.isEmpty()) {
                item {
                    Text(
                        "No favorites yet. Tap Search, then long-press a battery to add it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(batteries, key = { it.favorite.mac }) { b ->
                FavoriteCard(
                    b, now,
                    onOpen = { onOpen(b.favorite.mac) },
                    onRefresh = { scope.launch { repo.refresh(b.favorite.mac) } },
                    onRename = { renaming = b },
                    onMoveUp = { repo.move(b.favorite.mac, -1) },
                    onMoveDown = { repo.move(b.favorite.mac, +1) },
                    onRemove = { repo.remove(b.favorite.mac) },
                )
            }
            if (searching || discovered.isNotEmpty()) {
                item(key = "discovered-header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Discovered", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
                items(discovered, key = { "d-" + it.mac }) { hit ->
                    DiscoveredRow(
                        hit,
                        onAdd = { adding = hit },
                        onIdentify = {
                            identifying = hit
                            identifyResult = null
                            scope.launch { identifyResult = repo.identify(hit.mac) }
                        },
                    )
                }
            }
        }
    }

    adding?.let { hit ->
        NicknameDialog(
            title = "Add to favorites",
            initial = "",
            onDismiss = { adding = null },
            onConfirm = { repo.addFavorite(hit, it); adding = null },
        )
    }
    renaming?.let { b ->
        NicknameDialog(
            title = "Rename",
            initial = b.favorite.nickname,
            onDismiss = { renaming = null },
            onConfirm = { repo.rename(b.favorite.mac, it); renaming = null },
        )
    }
    identifying?.let { hit ->
        AlertDialog(
            onDismissRequest = { identifying = null },
            confirmButton = { TextButton(onClick = { identifying = null }) { Text("Close") } },
            title = { Text(hit.mac) },
            text = {
                when (val r = identifyResult) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Reading…")
                    }
                    else -> r.fold(
                        onSuccess = {
                            Text(
                                "${it.stateOfChargePct}% · ${volts(it.packVoltageMv)} · ${amps(it.currentMa)}\n" +
                                    "${ampHours(it.nominalCapacityMah)} · made ${it.productionDate}\n\n" +
                                    "Match this to the battery's display or charge level to know which one it is.",
                            )
                        },
                        onFailure = { Text("Couldn't read it: ${it.message}") },
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(
    b: BatteryUi,
    now: Long,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val r = b.reading
    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        b.favorite.nickname, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    SignalBars(r?.rssiDbm)
                }
                if (r == null) {
                    Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${r.stateOfChargePct}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(volts(r.packVoltageMv), modifier = Modifier.padding(bottom = 4.dp))
                        Text(amps(r.currentMa), modifier = Modifier.padding(bottom = 4.dp))
                        r.temperaturesDeciC.firstOrNull()?.let { Text(celsius(it), modifier = Modifier.padding(bottom = 4.dp)) }
                    }
                    LinearProgressIndicator(progress = { r.stateOfChargePct / 100f }, modifier = Modifier.fillMaxWidth())
                }
                StatusLine(b, now)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Refresh now") }, onClick = { menu = false; onRefresh() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMoveUp() })
            DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMoveDown() })
            DropdownMenuItem(text = { Text("Remove from favorites") }, onClick = { menu = false; onRemove() })
        }
    }
}

@Composable
fun StatusLine(b: BatteryUi, now: Long) {
    val r = b.reading
    val parts = buildList {
        r?.let {
            add(flowLabel(it))
            if (it.protections.isNotEmpty()) add("⚠ " + it.protections.joinToString { p -> protectionLabel(p) })
            add(formatAge(now, it.timestampMs))
        }
        when (val s = b.status) {
            RefreshStatus.Refreshing -> add("refreshing…")
            is RefreshStatus.Failed -> add("last try failed")
            RefreshStatus.Idle -> {}
        }
    }
    val warn = b.status is RefreshStatus.Failed || r?.protections?.isNotEmpty() == true
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoveredRow(hit: ScanHit, onAdd: () -> Unit, onIdentify: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { menu = true }, onLongClick = { menu = true })
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(hit.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge)
                Text(hit.mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SignalBars(hit.rssiDbm)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Add to favorites") }, onClick = { menu = false; onAdd() })
            DropdownMenuItem(text = { Text("Identify (read once)") }, onClick = { menu = false; onIdentify() })
        }
    }
}

@Composable
fun NicknameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("Nickname") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
