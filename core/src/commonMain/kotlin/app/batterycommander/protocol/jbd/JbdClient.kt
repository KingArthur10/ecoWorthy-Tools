package app.batterycommander.protocol.jbd

import app.batterycommander.transport.BleLink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

/** JBD client over an open [BleLink]. One request is in flight at a time. Reads, plus [setFets] (see docs/safety.md). */
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
                return@withLock exchange(JbdFrame.readRequest(command), command.code)
            } catch (e: TimeoutCancellationException) {
                lastError = JbdProtocolException("No response to ${command.name} within $responseTimeout")
            } catch (e: JbdProtocolException) {
                lastError = e
            }
        }
        throw lastError!!
    }

    /**
     * Switches the charge and/or discharge MOSFET; null leaves that one as it is. Returns the basic info read back
     * afterwards, which confirms the change (docs/safety.md rule 6), or throws [JbdProtocolException] if it didn't
     * take. Reads first, so the request the BMS ignores after a connect is never the write, and so the switch not
     * being changed keeps its current state. Does nothing if both are already as requested. The command is
     * absolute, so a lost reply is handled by reading back and resending.
     */
    suspend fun setFets(chargeOn: Boolean? = null, dischargeOn: Boolean? = null): JbdBasicInfo {
        var info = basicInfo()
        val charge = chargeOn ?: info.chargeFetOn
        val discharge = dischargeOn ?: info.dischargeFetOn
        repeat(attempts) {
            if (info.chargeFetOn == charge && info.dischargeFetOn == discharge) return info
            mutex.withLock {
                try {
                    exchange(JbdWrite.mosfetRequest(charge, discharge), JbdWrite.MOSFET_CONTROL)
                } catch (e: TimeoutCancellationException) {
                    // Reply lost or the write ignored: the read-back decides.
                }
            }
            delay(SETTLE)
            info = basicInfo()
        }
        if (info.chargeFetOn == charge && info.dischargeFetOn == discharge) return info
        throw JbdProtocolException(
            "MOSFETs didn't change: wanted charge ${onOff(charge)}, discharge ${onOff(discharge)}; " +
                "BMS reports charge ${onOff(info.chargeFetOn)}, discharge ${onOff(info.dischargeFetOn)}",
        )
    }

    private suspend fun basicInfo(): JbdBasicInfo = JbdParsers.basicInfo(read(JbdReadCommand.BASIC_INFO).payload)

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private companion object {
        /** Time for the BMS to switch and update its status before the read-back. */
        val SETTLE = 300.milliseconds
    }

    private suspend fun exchange(request: ByteArray, replyCode: Int): JbdResponse = coroutineScope {
        val assembler = JbdFrameAssembler()
        // Start collecting before writing so a fast reply isn't missed.
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            link.notifications
                .transform { chunk -> assembler.feed(chunk).forEach { emit(it) } }
                .first { it.size > 1 && (it[1].toInt() and 0xFF) == replyCode }
        }
        link.write(request)
        val frame = try {
            withTimeout(responseTimeout) { reply.await() }
        } finally {
            reply.cancel()
        }
        JbdFrame.parseResponse(frame)
    }
}
