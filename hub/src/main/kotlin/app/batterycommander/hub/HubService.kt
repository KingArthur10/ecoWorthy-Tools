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
import app.batterycommander.domain.updateGroup
import app.batterycommander.domain.withPollInterval
import app.batterycommander.domain.StoredState
import app.batterycommander.domain.toReading
import app.batterycommander.hub.bluez.BluezBle
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
            mutate { it.copy(readings = it.readings + (mac to r)) }
            _statuses.update { it + (mac to PollStatus(PollStatus.State.IDLE, atMs = clock())) }
        }.onFailure { e ->
            _statuses.update { it + (mac to PollStatus(PollStatus.State.FAILED, e.message ?: e.toString(), clock())) }
        }
        return result
    }

    suspend fun refreshAll() {
        for (fav in _state.value.favorites) refresh(fav.mac)
    }

    /**
     * Every [checkEvery], picks up external edits to the state file, then refreshes each favorite
     * whose reading is older than [pollInterval]. In manual mode it reads nothing. A battery that
     * just failed waits [failureBackoff] before its next try, so one out-of-range battery doesn't
     * monopolize the radio. The interval is re-read each pass, so changes apply within [checkEvery].
     */
    suspend fun runPoller(checkEvery: Duration = 30.seconds, failureBackoff: Duration = 2.minutes) {
        while (true) {
            reloadIfChangedExternally()
            val interval = pollInterval
            for (fav in if (interval == null) emptyList() else _state.value.favorites) {
                val last = _state.value.readings[fav.mac]?.timestampMs ?: 0
                val status = _statuses.value[fav.mac]
                val backingOff = status?.state == PollStatus.State.FAILED &&
                    clock() - (status.atMs ?: 0) < failureBackoff.inWholeMilliseconds
                if (clock() - last >= interval!!.inWholeMilliseconds && !backingOff) refresh(fav.mac)
            }
            delay(checkEvery)
        }
    }

    /** Takes favorites and groups from the file if it was edited externally; keeps our newer readings. */
    fun reloadIfChangedExternally() {
        if (!store.changedExternally()) return
        val fromDisk = store.load()
        mutate { current ->
            fromDisk.copy(readings = fromDisk.readings.filterKeys { mac -> fromDisk.favorites.any { it.mac == mac } } +
                current.readings.filterKeys { mac -> fromDisk.favorites.any { it.mac == mac } }
                    .filter { (mac, r) -> r.timestampMs >= (fromDisk.readings[mac]?.timestampMs ?: 0) })
        }
    }

    private val saveLock = Any()

    /** Applies an edit and saves. Validation errors from [change] propagate and leave state untouched. */
    private fun mutate(change: (StoredState) -> StoredState) {
        synchronized(saveLock) {
            _state.update(change)
            store.save(_state.value)
        }
    }
}
