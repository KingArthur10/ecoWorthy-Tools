package app.batterycommander.protocol.jbd

import app.batterycommander.transport.BleLink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** GATT layout of the JBD BLE module (verified on firmware 6.6). */
object JbdGatt {
    const val SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "0000ff01-0000-1000-8000-00805f9b34fb"
    const val WRITE = "0000ff02-0000-1000-8000-00805f9b34fb"
}

/** Everything a full read of one battery returns. */
data class JbdSnapshot(
    val basic: JbdBasicInfo,
    val cellVoltagesMv: List<Int>,
    val model: String,
)

/** Read-only JBD client over an open [BleLink]. One request is in flight at a time. */
class JbdClient(
    private val link: BleLink,
    // Replies arrive in ~75 ms. The BMS also tends to ignore the first request after connecting
    // (seen on both BlueZ and Android), so a short timeout keeps that retry cheap.
    private val responseTimeout: Duration = 1.seconds,
    private val attempts: Int = 3,
) {
    private val mutex = Mutex()

    suspend fun readSnapshot(): JbdSnapshot {
        val basic = JbdParsers.basicInfo(read(JbdReadCommand.BASIC_INFO).payload)
        val cells = JbdParsers.cellVoltages(read(JbdReadCommand.CELL_VOLTAGES).payload)
        val model = JbdParsers.hardwareVersion(read(JbdReadCommand.HARDWARE_VERSION).payload)
        return JbdSnapshot(basic, cells, model)
    }

    /**
     * Sends a read request and returns its validated response, retrying on timeout or a
     * corrupt frame. Throws the last failure once [attempts] are used up.
     */
    suspend fun read(command: JbdReadCommand): JbdResponse = mutex.withLock {
        var lastError: Exception? = null
        repeat(attempts) {
            try {
                return@withLock requestOnce(command)
            } catch (e: TimeoutCancellationException) {
                lastError = JbdProtocolException("No response to ${command.name} within $responseTimeout")
            } catch (e: JbdProtocolException) {
                lastError = e
            }
        }
        throw lastError!!
    }

    private suspend fun requestOnce(command: JbdReadCommand): JbdResponse = coroutineScope {
        val assembler = JbdFrameAssembler()
        // Start collecting before writing so a fast reply isn't missed.
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            link.notifications
                .transform { chunk -> assembler.feed(chunk).forEach { emit(it) } }
                .first { it.size > 1 && (it[1].toInt() and 0xFF) == command.code }
        }
        link.write(JbdFrame.readRequest(command))
        val frame = try {
            withTimeout(responseTimeout) { reply.await() }
        } finally {
            reply.cancel()
        }
        JbdFrame.parseResponse(frame)
    }
}
