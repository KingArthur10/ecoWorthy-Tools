package app.batterycommander.hub

import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.DiscoveredDevice
import app.batterycommander.domain.Wiring
import app.batterycommander.domain.addFavorite
import app.batterycommander.domain.assignGroup
import app.batterycommander.domain.createGroup
import app.batterycommander.domain.deleteGroup
import app.batterycommander.domain.moveFavorite
import app.batterycommander.domain.normalizeMac
import app.batterycommander.domain.removeFavorite
import app.batterycommander.domain.renameFavorite
import app.batterycommander.domain.SocThresholds
import app.batterycommander.domain.setFavoriteThresholds
import app.batterycommander.domain.setGroupThresholds
import app.batterycommander.domain.setTotalThresholds
import app.batterycommander.domain.withReading
import app.batterycommander.domain.ChargeAction
import app.batterycommander.domain.Favorite
import app.batterycommander.domain.chargeLimitAction
import app.batterycommander.domain.enforcedLimitPct
import app.batterycommander.domain.pollIntervalMs
import app.batterycommander.domain.setChargeLimit
import java.util.concurrent.ConcurrentHashMap
import app.batterycommander.domain.updateGroup
import app.batterycommander.domain.withPollInterval
import app.batterycommander.domain.StoredState
import app.batterycommander.domain.toReading
import app.batterycommander.hub.bluez.BluezBle
import app.batterycommander.hub.store.CommandRecord
import app.batterycommander.hub.store.HistoryDb
import app.batterycommander.hub.store.StateStore
import app.batterycommander.protocol.jbd.JbdClient
import app.batterycommander.protocol.jbd.JbdGatt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Reads one battery fully. Real implementation: [BluezReader]; tests use a fake. */
fun interface BatteryReader {
    suspend fun read(mac: String): BatteryReading
}

/**
 * Switches a battery's charge MOSFET and returns a full reading taken afterwards, which must confirm it.
 * Real implementation: [BluezController]. The only state-changing operation the hub has (docs/safety.md).
 */
fun interface BatteryController {
    suspend fun setCharge(mac: String, on: Boolean): BatteryReading
}

class BluezController(private val ble: BluezBle, private val clock: () -> Long = System::currentTimeMillis) : BatteryController {
    override suspend fun setCharge(mac: String, on: Boolean): BatteryReading {
        val link = ble.connect(mac, writeUuid = JbdGatt.WRITE, notifyUuid = JbdGatt.NOTIFY)
        try {
            val client = JbdClient(link)
            client.setFets(chargeOn = on)
            return client.readSnapshot().toReading(clock(), link.rssi)
        } finally {
            link.close()
        }
    }
}

/** Finds nearby batteries. Real implementation: [BluezScanner]. */
fun interface BatteryScanner {
    suspend fun scan(duration: Duration): List<DiscoveredDevice>
}

class BluezScanner(private val ble: BluezBle) : BatteryScanner {
    override suspend fun scan(duration: Duration) = ble.scan(JbdGatt.SERVICE, duration)
}

class BluezReader(private val ble: BluezBle, private val clock: () -> Long = System::currentTimeMillis) : BatteryReader {
    override suspend fun read(mac: String): BatteryReading {
        val link = ble.connect(mac, writeUuid = JbdGatt.WRITE, notifyUuid = JbdGatt.NOTIFY)
        try {
            return JbdClient(link).readSnapshot().toReading(clock(), link.rssi)
        } finally {
            link.close()
        }
    }
}

@Serializable
data class ScanState(
    val scanning: Boolean = false,
    /** Batteries found by the last scan that aren't favorites, strongest first. */
    val devices: List<DiscoveredDevice> = emptyList(),
    val finishedAtMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class PollStatus(
    val state: State = State.IDLE,
    val message: String? = null,
    val atMs: Long? = null,
) {
    enum class State { IDLE, REFRESHING, FAILED }
}

/**
 * The hub's polling loop and state. One battery is read at a time (the radio and each battery
 * allow one connection), mirroring the phone's BatteryRepository.
 */
class HubService(
    private val store: StateStore,
    private val history: HistoryDb,
    private val reader: BatteryReader,
    private val scanner: BatteryScanner = BatteryScanner { emptyList() },
    private val controller: BatteryController = BatteryController { _, _ -> error("No battery control in this hub") },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<StoredState> = _state.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, PollStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PollStatus>> = _statuses.asStateFlow()

    /** Time between automatic reads of each battery, or null for manual-only (the radio stays idle). */
    val pollInterval: Duration?
        get() = _state.value.settings.let { if (it.isManual) null else it.pollIntervalMinutes.minutes }

    fun setPollInterval(minutes: Int) = mutate { it.withPollInterval(minutes) }

    /** One radio operation at a time: reads and scans both take this. */
    private val radioLock = Mutex()

    private val _scan = MutableStateFlow(ScanState())

    /** Last scan's results, minus anything that's since become a favorite. */
    val scan: StateFlow<ScanState> get() = _scan.asStateFlow()

    fun scanResults(): ScanState = _scan.value.let { s ->
        val favs = _state.value.favorites.map { it.mac }.toSet()
        s.copy(devices = s.devices.filter { it.mac !in favs })
    }

    /** Scans for [duration]. Returns false if a scan is already running. */
    suspend fun runScan(duration: Duration = 15.seconds): Boolean {
        if (_scan.value.scanning) return false
        _scan.value = _scan.value.copy(scanning = true, error = null)
        val result = radioLock.withLock { runCatching { scanner.scan(duration) } }
        _scan.value = ScanState(
            scanning = false,
            devices = result.getOrDefault(_scan.value.devices),
            finishedAtMs = clock(),
            error = result.exceptionOrNull()?.let { it.message ?: it.toString() },
        )
        return true
    }

    /** One-off read of any battery (not saved), so the user can tell which physical one it is. */
    suspend fun identify(mac: String): Result<BatteryReading> = radioLock.withLock { runCatching { reader.read(normalizeMac(mac)) } }

    // --- Favorites and groups (rules in core FleetEdits) ---------------------------------------

    fun addFavorite(mac: String, nickname: String) {
        val m = normalizeMac(mac)
        val bleName = _scan.value.devices.firstOrNull { it.mac == m }?.name
        mutate { it.addFavorite(m, nickname, bleName) }
    }

    fun renameFavorite(mac: String, nickname: String) = mutate { requireFavorite(it, mac).renameFavorite(mac, nickname) }

    fun removeFavorite(mac: String) = mutate { requireFavorite(it, mac).removeFavorite(mac) }

    fun moveFavorite(mac: String, delta: Int) = mutate { requireFavorite(it, mac).moveFavorite(mac, delta) }

    fun assignGroup(mac: String, groupId: String?) = mutate { requireFavorite(it, mac).assignGroup(mac, groupId) }

    fun createGroup(name: String, wiring: Wiring): String {
        val id = UUID.randomUUID().toString()
        mutate { it.createGroup(id, name, wiring) }
        return id
    }

    fun updateGroup(id: String, name: String?, wiring: Wiring?) = mutate { requireGroup(it, id).updateGroup(id, name, wiring) }

    fun deleteGroup(id: String) = mutate { requireGroup(it, id).deleteGroup(id) }

    fun setFavoriteThresholds(mac: String, t: SocThresholds) {
        mutate { requireFavorite(it, mac).setFavoriteThresholds(mac, t) }
        rearmed += mac
    }

    /** Turns the charge limit on or off for one battery; it's checked on the next poll pass (within ~30 s). */
    fun setChargeLimit(mac: String, enabled: Boolean) {
        mutate { it.setChargeLimit(mac, enabled) }
        rearmed += mac
    }

    /**
     * Batteries whose limit just changed (or was turned off): they're read on the next pass, and may resume
     * charging below the new limit without waiting out the resume gap.
     */
    private val rearmed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun setGroupThresholds(id: String, t: SocThresholds) = mutate { requireGroup(it, id).setGroupThresholds(id, t) }

    fun setTotalThresholds(t: SocThresholds) = mutate { it.setTotalThresholds(t) }

    fun isFavorite(mac: String) = _state.value.favorites.any { it.mac == mac }

    private fun requireFavorite(s: StoredState, mac: String): StoredState {
        if (s.favorites.none { it.mac == mac }) throw NoSuchElementException("Not a favorite: $mac")
        return s
    }

    private fun requireGroup(s: StoredState, id: String): StoredState {
        if (s.groups.none { it.id == id }) throw NoSuchElementException("No such group: $id")
        return s
    }

    suspend fun refresh(mac: String): Result<BatteryReading> {
        _statuses.update { it + (mac to PollStatus(PollStatus.State.REFRESHING, atMs = clock())) }
        val result = radioLock.withLock { runCatching { reader.read(mac) } }
        result.onSuccess { r ->
            history.record(mac, r)
            mutate { it.withReading(mac, r) }
            _statuses.update { it + (mac to PollStatus(PollStatus.State.IDLE, atMs = clock())) }
            applyChargeLimit(mac, r)
        }.onFailure { e ->
            _statuses.update { it + (mac to PollStatus(PollStatus.State.FAILED, e.message ?: e.toString(), clock())) }
        }
        return result
    }

    /** Applies the battery's charge limit to a fresh reading (core chargeLimitAction), logging any switch as "profile". */
    private suspend fun applyChargeLimit(mac: String, r: BatteryReading) {
        val s = _state.value
        val fav = s.favorites.firstOrNull { it.mac == mac } ?: return
        if (s.groups.firstOrNull { it.id == fav.groupId }?.wiring == Wiring.SERIES) return
        val action = chargeLimitAction(r, fav.enforcedLimitPct, rearmed = rearmed.remove(mac)) ?: return
        setCharge(mac, on = action == ChargeAction.TURN_ON, source = "profile")
    }

    /**
     * Turns a favorite's charge MOSFET on or off, confirms it from a read-back, stores that reading, and logs the
     * command whatever the outcome. Refuses batteries in a series group: a string must switch together (docs/safety.md
     * rule 3), which isn't built yet. [source] is "user" or "profile".
     */
    suspend fun setCharge(mac: String, on: Boolean, source: String): Result<BatteryReading> {
        val s = _state.value
        val fav = s.favorites.firstOrNull { it.mac == mac } ?: throw NoSuchElementException("Not a favorite: $mac")
        val group = s.groups.firstOrNull { it.id == fav.groupId }
        require(group?.wiring != Wiring.SERIES) {
            "${fav.nickname} is in a series group (${group?.name}); series strings must switch together, which isn't supported yet"
        }
        _statuses.update { it + (mac to PollStatus(PollStatus.State.REFRESHING, atMs = clock())) }
        val result = radioLock.withLock { runCatching { controller.setCharge(mac, on) } }
        result.onSuccess { r ->
            if (r.chargeFetOn != on) return@onSuccess
            history.record(mac, r)
            mutate { it.withReading(mac, r) }
        }
        val checked = result.mapCatching { r ->
            check(r.chargeFetOn == on) { "Read-back shows charge ${if (r.chargeFetOn) "on" else "off"}" }
            r
        }
        history.recordCommand(
            CommandRecord(clock(), mac, "charge ${if (on) "on" else "off"}", source, "hub", checked.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "ok"),
        )
        _statuses.update {
            it + (mac to checked.fold({ PollStatus(PollStatus.State.IDLE, atMs = clock()) }, { e -> PollStatus(PollStatus.State.FAILED, e.message, clock()) }))
        }
        return checked
    }

    suspend fun refreshAll() {
        for (fav in _state.value.favorites) refresh(fav.mac)
    }

    /**
     * Every [checkEvery], picks up external edits to the state file, then refreshes each favorite
     * whose reading is older than [pollInterval] (or 1 min for one charging close to its charge limit), and any whose
     * limit just changed. In manual mode it reads nothing, so charge limits are only checked on manual refreshes. A battery that
     * just failed waits [failureBackoff] before its next try, so one out-of-range battery doesn't
     * monopolize the radio. The interval is re-read each pass, so changes apply within [checkEvery].
     */
    suspend fun runPoller(checkEvery: Duration = 30.seconds, failureBackoff: Duration = 2.minutes) {
        while (true) {
            reloadIfChangedExternally()
            val interval = pollInterval
            for (fav in if (interval == null) emptyList() else _state.value.favorites) {
                val reading = _state.value.readings[fav.mac]
                val last = reading?.timestampMs ?: 0
                val status = _statuses.value[fav.mac]
                val backingOff = status?.state == PollStatus.State.FAILED &&
                    clock() - (status.atMs ?: 0) < failureBackoff.inWholeMilliseconds
                val due = pollIntervalMs(interval!!.inWholeMilliseconds, reading, fav.enforcedLimitPct)
                if ((clock() - last >= due || fav.mac in rearmed) && !backingOff) refresh(fav.mac)
            }
            delay(checkEvery)
        }
    }

    /** Takes favorites and groups from the file if it was edited externally; keeps our newer readings and samples. */
    fun reloadIfChangedExternally() {
        if (!store.changedExternally()) return
        val fromDisk = store.load()
        val keep = fromDisk.favorites.map { it.mac }.toSet()
        // A limit changed outside the hub (`battery-hub charge-limit`, `thresholds`): re-check those batteries now.
        val before = _state.value.favorites.associateBy { it.mac }
        fromDisk.favorites.filter { f -> before[f.mac]?.let { limitKey(it) != limitKey(f) } == true }.forEach { rearmed += it.mac }
        mutate { current ->
            fromDisk.copy(
                readings = fromDisk.readings.filterKeys { it in keep } +
                    current.readings.filterKeys { it in keep }
                        .filter { (mac, r) -> r.timestampMs >= (fromDisk.readings[mac]?.timestampMs ?: 0) },
                recent = current.recent.filterKeys { it in keep },
            )
        }
    }

    private fun limitKey(f: Favorite) = f.chargeLimit to f.thresholds.high

    private val saveLock = Any()

    /** Applies an edit and saves. Validation errors from [change] propagate and leave state untouched. */
    private fun mutate(change: (StoredState) -> StoredState) {
        synchronized(saveLock) {
            _state.update(change)
            store.save(_state.value)
        }
    }
}
