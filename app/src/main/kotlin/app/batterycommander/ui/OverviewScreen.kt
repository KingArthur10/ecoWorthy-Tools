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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batterycommander.ble.ScanHit
import app.batterycommander.data.BatteryRepository
import app.batterycommander.data.BatteryUi
import app.batterycommander.data.GroupUi
import app.batterycommander.data.RefreshStatus
import app.batterycommander.domain.AggregateStats
import app.batterycommander.domain.BatteryGroup
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.Wiring
import app.batterycommander.domain.capacityMah
import app.batterycommander.domain.formatAge
import app.batterycommander.domain.nominalVoltageMv
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(repo: BatteryRepository, onOpen: (String) -> Unit) {
    val batteries by repo.batteries.collectAsStateWithLifecycle()
    val overview by repo.overview.collectAsStateWithLifecycle()
    val groups by repo.groups.collectAsStateWithLifecycle()
    val discovered by repo.discovered.collectAsStateWithLifecycle()
    val searching by repo.searching.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()

    var adding by remember { mutableStateOf<ScanHit?>(null) }
    var renaming by remember { mutableStateOf<BatteryUi?>(null) }
    var identifying by remember { mutableStateOf<ScanHit?>(null) }
    var identifyResult by remember { mutableStateOf<Result<BatteryReading>?>(null) }
    var pickingGroupFor by remember { mutableStateOf<BatteryUi?>(null) }
    var newGroupFor by remember { mutableStateOf<BatteryUi?>(null) }
    var renamingGroup by remember { mutableStateOf<BatteryGroup?>(null) }
    var rewiringGroup by remember { mutableStateOf<BatteryGroup?>(null) }

    val favoriteCard: @Composable (BatteryUi) -> Unit = { b ->
        FavoriteCard(
            b, now,
            onOpen = { onOpen(b.favorite.mac) },
            onRefresh = { scope.launch { repo.refresh(b.favorite.mac) } },
            onRename = { renaming = b },
            onGroup = { pickingGroupFor = b },
            onMoveUp = { repo.move(b.favorite.mac, -1) },
            onMoveDown = { repo.move(b.favorite.mac, +1) },
            onRemove = { repo.remove(b.favorite.mac) },
        )
    }

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
            val total = overview.total
            if (batteries.size >= 2 && total != null) {
                item(key = "total") { TotalCard("All batteries", total, now) }
            }
            for (g in overview.groups) {
                item(key = "g-" + g.group.id) {
                    GroupCard(
                        g, now,
                        onRename = { renamingGroup = g.group },
                        onRewire = { rewiringGroup = g.group },
                        onDelete = { repo.deleteGroup(g.group.id) },
                    )
                }
                items(g.members, key = { it.favorite.mac }) { b ->
                    Box(Modifier.padding(start = 16.dp)) { favoriteCard(b) }
                }
            }
            if (overview.groups.isNotEmpty() && overview.ungrouped.isNotEmpty()) {
                item(key = "ungrouped-header") { SectionHeader("Not in a group") }
            }
            items(overview.ungrouped, key = { it.favorite.mac }) { favoriteCard(it) }
            if (searching || discovered.isNotEmpty()) {
                item(key = "discovered-header") {
                    SectionHeader("Discovered", busy = searching)
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
    pickingGroupFor?.let { b ->
        GroupPickerDialog(
            groups = groups,
            current = b.favorite.groupId,
            onDismiss = { pickingGroupFor = null },
            onPick = { repo.assignGroup(b.favorite.mac, it); pickingGroupFor = null },
            onNew = { newGroupFor = b; pickingGroupFor = null },
        )
    }
    newGroupFor?.let { b ->
        GroupDialog(
            title = "New group",
            initialName = "",
            initialWiring = Wiring.PARALLEL,
            onDismiss = { newGroupFor = null },
            onConfirm = { name, wiring ->
                repo.assignGroup(b.favorite.mac, repo.createGroup(name, wiring))
                newGroupFor = null
            },
        )
    }
    renamingGroup?.let { g ->
        NicknameDialog(
            title = "Rename group",
            initial = g.name,
            label = "Group name",
            onDismiss = { renamingGroup = null },
            onConfirm = { repo.renameGroup(g.id, it); renamingGroup = null },
        )
    }
    rewiringGroup?.let { g ->
        GroupDialog(
            title = "Group wiring",
            initialName = g.name,
            initialWiring = g.wiring,
            onDismiss = { rewiringGroup = null },
            onConfirm = { name, wiring ->
                repo.renameGroup(g.id, name)
                repo.setWiring(g.id, wiring)
                rewiringGroup = null
            },
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
    onGroup: () -> Unit,
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
                    Text(
                        "${ah(r.remainingCapacityMah)} of ${ah(r.capacityMah)} · " +
                            "${energy(r.remainingCapacityMah.toLong() * r.nominalVoltageMv / 1000)} of " +
                            energy(r.capacityMah.toLong() * r.nominalVoltageMv / 1000),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                StatusLine(b, now)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Refresh now") }, onClick = { menu = false; onRefresh() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Group…") }, onClick = { menu = false; onGroup() })
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
fun NicknameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    label: String = "Nickname",
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionHeader(title: String, busy: Boolean = false) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** Totals for every favorite. */
@Composable
private fun TotalCard(title: String, stats: AggregateStats, now: Long) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            AggregateBody(stats, now)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(g: GroupUi, now: Long, onRename: () -> Unit, onRewire: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 8.dp)) {
        Card(
            Modifier.fillMaxWidth().combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        g.group.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    Text(wiringLabel(g.group.wiring), style = MaterialTheme.typography.labelMedium)
                }
                when {
                    g.members.isEmpty() -> Text("No batteries yet. Long-press a battery → Group…", style = MaterialTheme.typography.bodySmall)
                    g.stats == null -> Text("No data yet", style = MaterialTheme.typography.bodySmall)
                    else -> AggregateBody(g.stats, now)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename group") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Change wiring") }, onClick = { menu = false; onRewire() })
            DropdownMenuItem(text = { Text("Delete group") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun AggregateBody(s: AggregateStats, now: Long) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${s.stateOfChargePct}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("${ah(s.remainingMah)} of ${ah(s.capacityMah)}", modifier = Modifier.padding(bottom = 4.dp))
    }
    LinearProgressIndicator(progress = { s.stateOfChargePct / 100f }, modifier = Modifier.fillMaxWidth())
    Text(
        buildList {
            add("${energy(s.energyRemainingMwh)} of ${energy(s.energyCapacityMwh)}")
            s.voltageMv?.let { add(volts(it)) }
            s.currentMa?.let { add(amps(it)) }
            add(watts(s.powerMw))
        }.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
    )
    val count = if (s.batteryCount == 1) "1 battery" else "${s.batteryCount} batteries"
    Text(
        if (s.isPartial) "$count · only ${s.reportingCount} have data · oldest ${formatAge(now, s.oldestReadingMs)}"
        else "$count · oldest data ${formatAge(now, s.oldestReadingMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = if (s.isPartial) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GroupPickerDialog(
    groups: List<BatteryGroup>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group") },
        text = {
            Column {
                for (g in groups) {
                    RadioRow("${g.name} (${wiringLabel(g.wiring).lowercase()})", selected = g.id == current) { onPick(g.id) }
                }
                RadioRow("No group", selected = current == null) { onPick(null) }
                TextButton(onClick = onNew) { Text("New group…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GroupDialog(
    title: String,
    initialName: String,
    initialWiring: Wiring,
    onDismiss: () -> Unit,
    onConfirm: (String, Wiring) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var wiring by remember { mutableStateOf(initialWiring) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Group name") }, singleLine = true)
                Text("How are these batteries wired?", style = MaterialTheme.typography.labelLarge)
                for (w in Wiring.entries) RadioRow(wiringLabel(w), selected = w == wiring) { wiring = w }
                Text(
                    "Parallel adds amp-hours. Series adds voltage, and the weakest battery sets the amp-hours. " +
                        "Energy (kWh) adds up either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, wiring) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
