package app.batterycommander.data

import android.util.Log
import app.batterycommander.ble.AndroidBle
import app.batterycommander.ble.ScanHit
import app.batterycommander.domain.AggregateStats
import app.batterycommander.domain.BatteryGroup
import app.batterycommander.domain.BatteryReading
import app.batterycommander.domain.Favorite
import app.batterycommander.domain.StoredState
import app.batterycommander.domain.Wiring
import app.batterycommander.domain.addFavorite
import app.batterycommander.domain.assignGroup
import app.batterycommander.domain.createGroup
import app.batterycommander.domain.deleteGroup
import app.batterycommander.domain.moveFavorite
import app.batterycommander.domain.removeFavorite
import app.batterycommander.domain.renameFavorite
import app.batterycommander.domain.summarize
import app.batterycommander.domain.updateGroup
import app.batterycommander.domain.toReading
import app.batterycommander.protocol.jbd.JbdClient
import app.batterycommander.protocol.jbd.JbdGatt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface RefreshStatus {
    data object Idle : RefreshStatus
    data object Refreshing : RefreshStatus
    data class Failed(val message: String, val atMs: Long) : RefreshStatus
}

data class BatteryUi(val favorite: Favorite, val reading: BatteryReading?, val status: RefreshStatus)

data class GroupUi(val group: BatteryGroup, val members: List<BatteryUi>, val stats: AggregateStats?)

/** Everything the overview screen shows, in display order. */
data class Overview(
    /** Totals across every favorite; null until at least one has a reading. */
    val total: AggregateStats?,
    val groups: List<GroupUi>,
    val ungrouped: List<BatteryUi>,
)

/**
 * Favorites, their cached readings, discovery, and polling. One BLE operation runs at a time:
 * batteries are polled sequentially, as docs/architecture/polling-and-caching.md specifies.
 */
class BatteryRepository(
    private val ble: AndroidBle,
    private val store: FavoritesStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(store.load())
    private val statuses = MutableStateFlow<Map<String, RefreshStatus>>(emptyMap())
    private val bleLock = Mutex()

    val batteries: StateFlow<List<BatteryUi>> = combine(state, statuses) { s, st ->
        s.favorites.map { BatteryUi(it, s.readings[it.mac], st[it.mac] ?: RefreshStatus.Idle) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val groups: StateFlow<List<BatteryGroup>> = state.map { it.groups }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val overview: StateFlow<Overview> = combine(state, batteries) { s, all ->
        val byMac = all.associateBy { it.favorite.mac }
        val summary = s.summarize()
        Overview(
            total = summary.total,
            groups = summary.groups.map { g -> GroupUi(g.group, g.members.mapNotNull { byMac[it.mac] }, g.stats) },
            ungrouped = summary.ungrouped.mapNotNull { byMac[it.mac] },
        )
    }.stateIn(scope, SharingStarted.Eagerly, Overview(null, emptyList(), emptyList()))

    private val _discovered = MutableStateFlow<List<ScanHit>>(emptyList())

    /** Batteries seen by the last search that aren't favorites, strongest signal first. */
    val discovered: StateFlow<List<ScanHit>> = combine(_discovered, state) { hits, s ->
        val favs = s.favorites.map { it.mac }.toSet()
        hits.filter { it.mac !in favs }.sortedByDescending { it.rssiDbm }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var searchJob: Job? = null

    /** Polling interval: a favorite whose reading is older than this gets refreshed. */
    var pollInterval: Duration = 10.minutes

    // --- Favorites -------------------------------------------------------------------------

    fun addFavorite(hit: ScanHit, nickname: String) {
        mutate { it.addFavorite(hit.mac, nickname, hit.name) }
        // Read it right away rather than leaving "No data yet" until the next poll.
        scope.launch { refresh(hit.mac) }
    }

    fun rename(mac: String, nickname: String) = mutate { it.renameFavorite(mac, nickname) }

    fun remove(mac: String) = mutate { it.removeFavorite(mac) }

    /** Moves a favorite up (-1) or down (+1) in the display order. */
    fun move(mac: String, delta: Int) = mutate { it.moveFavorite(mac, delta) }

    // --- Groups ----------------------------------------------------------------------------

    /** Creates a group and returns its id. */
    fun createGroup(name: String, wiring: Wiring): String {
        val id = UUID.randomUUID().toString()
        mutate { it.createGroup(id, name, wiring) }
        return id
    }

    fun renameGroup(id: String, name: String) = mutate { it.updateGroup(id, name = name) }

    fun setWiring(id: String, wiring: Wiring) = mutate { it.updateGroup(id, wiring = wiring) }

    /** Deletes the group; its batteries become ungrouped (they stay favorites). */
    fun deleteGroup(id: String) = mutate { it.deleteGroup(id) }

    /** Puts a battery in a group, or takes it out with null. A battery is in at most one group. */
    fun assignGroup(mac: String, groupId: String?) = mutate { it.assignGroup(mac, groupId) }

    // --- Search ----------------------------------------------------------------------------

    fun search(duration: Duration = 15.seconds) {
        if (searchJob?.isActive == true) return
        _discovered.value = emptyList()
        searchJob = scope.launch {
            _searching.value = true
            try {
                withTimeoutOrNull(duration) {
                    ble.scan(JbdGatt.SERVICE).collect { hit ->
                        _discovered.update { list -> list.filterNot { it.mac == hit.mac } + hit }
                    }
                }
            } catch (e: Exception) {
                // Scan failures (Bluetooth off, too many scans) end the search; the list keeps what was found.
            } finally {
                _searching.value = false
            }
        }
    }

    // --- Reading ---------------------------------------------------------------------------

    /** Full read of a favorite now; updates the cache. */
    suspend fun refresh(mac: String) {
        statuses.update { it + (mac to RefreshStatus.Refreshing) }
        val result = runCatching { read(mac) }
        result.onSuccess { reading -> mutate { it.copy(readings = it.readings + (mac to reading)) } }
        statuses.update {
            it + (mac to (result.exceptionOrNull()?.let { e -> RefreshStatus.Failed(e.message ?: e.toString(), clock()) } ?: RefreshStatus.Idle))
        }
    }

    fun refreshAll() {
        scope.launch { state.value.favorites.forEach { refresh(it.mac) } }
    }

    /** One-off read of a non-favorite, so the user can tell which physical battery it is. */
    suspend fun identify(mac: String): Result<BatteryReading> = runCatching { read(mac) }

    /**
     * Refreshes every favorite whose reading is older than [pollInterval], checking every 30 s.
     * Runs until cancelled; the activity runs it while visible (background monitoring comes later).
     */
    suspend fun pollWhileActive() {
        while (true) {
            if (ble.hasPermissions() && ble.isEnabled) {
                for (fav in state.value.favorites) {
                    val last = state.value.readings[fav.mac]?.timestampMs ?: 0
                    val failedRecently = (statuses.value[fav.mac] as? RefreshStatus.Failed)?.let { clock() - it.atMs < 60_000 } == true
                    if (clock() - last >= pollInterval.inWholeMilliseconds && !failedRecently) refresh(fav.mac)
                }
            }
            delay(30.seconds)
        }
    }

    private suspend fun read(mac: String): BatteryReading = bleLock.withLock {
        val link = ble.connect(mac, JbdGatt.SERVICE, JbdGatt.WRITE, JbdGatt.NOTIFY)
        try {
            JbdClient(link).readSnapshot().toReading(clock(), link.rssiDbm)
        } finally {
            link.close()
        }
    }

    private val saveLock = Mutex()

    private companion object {
        const val TAG = "BatteryRepository"
    }

    private fun mutate(change: (StoredState) -> StoredState) {
        state.update(change)
        // Save whatever is current when the lock is acquired, so saves can't land out of order.
        scope.launch(Dispatchers.IO) {
            saveLock.withLock {
                // A failed save must not crash the app; state stays in memory and the next change retries.
                runCatching { store.save(state.value) }.onFailure { Log.e(TAG, "Saving favorites failed", it) }
            }
        }
    }
}
