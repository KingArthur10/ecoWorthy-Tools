package app.batterycommander.hub

import app.batterycommander.domain.BatteryReading
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Reads one battery fully. Real implementation: [BluezReader]; tests use a fake. */
fun interface BatteryReader {
    suspend fun read(mac: String): BatteryReading
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
    val pollInterval: Duration = 10.minutes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<StoredState> = _state.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, PollStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PollStatus>> = _statuses.asStateFlow()

    private val readLock = Mutex()

    suspend fun refresh(mac: String): Result<BatteryReading> {
        _statuses.update { it + (mac to PollStatus(PollStatus.State.REFRESHING, atMs = clock())) }
        val result = readLock.withLock { runCatching { reader.read(mac) } }
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
     * whose reading is older than [pollInterval]. A battery that just failed waits [failureBackoff]
     * before its next try, so one out-of-range battery doesn't monopolize the radio.
     */
    suspend fun runPoller(checkEvery: Duration = 30.seconds, failureBackoff: Duration = 2.minutes) {
        while (true) {
            reloadIfChangedExternally()
            for (fav in _state.value.favorites) {
                val last = _state.value.readings[fav.mac]?.timestampMs ?: 0
                val status = _statuses.value[fav.mac]
                val backingOff = status?.state == PollStatus.State.FAILED &&
                    clock() - (status.atMs ?: 0) < failureBackoff.inWholeMilliseconds
                if (clock() - last >= pollInterval.inWholeMilliseconds && !backingOff) refresh(fav.mac)
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

    private fun mutate(change: (StoredState) -> StoredState) {
        _state.update(change)
        synchronized(saveLock) { store.save(_state.value) }
    }
}
