package app.batterycommander.protocol.jbd

import app.batterycommander.transport.BleLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A BMS that keeps MOSFET state: 0x03 replies carry it in payload byte 20 (bit 0 = charge on, bit 1 = discharge
 * on), and 0xE1 writes change it. Like the real one, it ignores the first request after connecting.
 */
private class FakeFetBms(
    var charge: Boolean = true,
    var discharge: Boolean = true,
    /** False = the BMS acknowledges writes but doesn't switch. */
    private val obeys: Boolean = true,
) : BleLink {
    private val basic = Captures.all[0].basic.hexToBytes()
    private val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val writes = mutableListOf<ByteArray>()

    override val notifications: SharedFlow<ByteArray> = flow

    override suspend fun write(data: ByteArray) {
        writes += data
        if (writes.size == 1) return
        val reply = when (data[2].toInt() and 0xFF) {
            0x03 -> {
                val payload = basic.copyOfRange(4, basic.size - 3)
                payload[20] = ((if (charge) 1 else 0) or (if (discharge) 2 else 0)).toByte()
                frame(0x03, payload)
            }
            JbdWrite.MOSFET_CONTROL -> {
                val v = data[5].toInt()
                if (obeys) {
                    charge = v and 1 == 0
                    discharge = v and 2 == 0
                }
                frame(JbdWrite.MOSFET_CONTROL, ByteArray(0))
            }
            else -> error("unexpected request")
        }
        reply.asList().chunked(20).forEach { flow.emit(it.toByteArray()) }
    }

    private fun frame(cmd: Int, payload: ByteArray): ByteArray {
        val body = listOf(0, payload.size) + payload.map { it.toInt() and 0xFF }
        val chk = JbdFrame.checksum(body)
        return bytesOf(JbdFrame.START, cmd, 0, payload.size) + payload + bytesOf(chk shr 8, chk and 0xFF, JbdFrame.END)
    }
}

class JbdWriteTest {
    @Test
    fun buildsMosfetFrames() {
        assertContentEquals("dd5ae1020000ff1d77".hexToBytes(), JbdWrite.mosfetRequest(chargeOn = true, dischargeOn = true))
        assertContentEquals("dd5ae1020001ff1c77".hexToBytes(), JbdWrite.mosfetRequest(chargeOn = false, dischargeOn = true))
        assertContentEquals("dd5ae1020002ff1b77".hexToBytes(), JbdWrite.mosfetRequest(chargeOn = true, dischargeOn = false))
    }

    @Test
    fun turnsChargeOffKeepingDischargeAndReadsBack() = runTest {
        val bms = FakeFetBms()
        val info = JbdClient(bms).setFets(chargeOn = false)
        assertFalse(info.chargeFetOn)
        assertTrue(info.dischargeFetOn)
        assertTrue(bms.discharge)
        // The first request (ignored after connect) was a read, never the write.
        assertEquals(JbdFrame.READ, bms.writes.first()[1].toInt() and 0xFF)
        assertEquals(1, bms.writes.count { it[1].toInt() and 0xFF == JbdWrite.WRITE })

        JbdClient(bms).setFets(chargeOn = true)
        assertTrue(bms.charge)
    }

    @Test
    fun writesNothingWhenAlreadyInState() = runTest {
        val bms = FakeFetBms()
        JbdClient(bms).setFets(chargeOn = true)
        assertTrue(bms.writes.none { it[1].toInt() and 0xFF == JbdWrite.WRITE })
    }

    @Test
    fun failsWhenTheReadBackDisagrees() = runTest {
        val bms = FakeFetBms(obeys = false)
        assertFailsWith<JbdProtocolException> { JbdClient(bms).setFets(chargeOn = false) }
        assertTrue(bms.charge)
    }
}
