package app.batterycommander.protocol.jbd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JbdFrameTest {
    @Test
    fun buildsKnownReadRequests() {
        assertContentEquals("dda50300fffd77".hexToBytes(), JbdFrame.readRequest(JbdReadCommand.BASIC_INFO))
        assertContentEquals("dda50400fffc77".hexToBytes(), JbdFrame.readRequest(JbdReadCommand.CELL_VOLTAGES))
        assertContentEquals("dda50500fffb77".hexToBytes(), JbdFrame.readRequest(JbdReadCommand.HARDWARE_VERSION))
    }

    @Test
    fun parsesEveryCapturedFrame() {
        for (c in Captures.all) {
            assertEquals(0x03, JbdFrame.parseResponse(c.basic.hexToBytes()).command)
            assertEquals(0x04, JbdFrame.parseResponse(c.cells.hexToBytes()).command)
            assertEquals(0x05, JbdFrame.parseResponse(c.hwver.hexToBytes()).command)
        }
    }

    @Test
    fun rejectsCorruptChecksum() {
        val frame = Captures.all[0].cells.hexToBytes().also { it[5] = (it[5] + 1).toByte() }
        assertFailsWith<JbdProtocolException> { JbdFrame.parseResponse(frame) }
    }

    @Test
    fun rejectsLengthMismatch() {
        val frame = Captures.all[0].cells.hexToBytes()
        assertFailsWith<JbdProtocolException> { JbdFrame.parseResponse(frame.copyOfRange(0, frame.size - 2) + byteArrayOf(0x77)) }
    }

    @Test
    fun rejectsBadMarkers() {
        val frame = Captures.all[0].cells.hexToBytes()
        assertFailsWith<JbdProtocolException> { JbdFrame.parseResponse(frame.copyOf().also { it[0] = 0x00 }) }
        assertFailsWith<JbdProtocolException> { JbdFrame.parseResponse(frame.copyOf().also { it[it.size - 1] = 0x00 }) }
        assertFailsWith<JbdProtocolException> { JbdFrame.parseResponse(ByteArray(3)) }
    }

    @Test
    fun rejectsErrorStatus() {
        // DD 04 80 00 <chk> 77: status 0x80, no data; checksum covers status + length.
        val chk = JbdFrame.checksum(listOf(0x80, 0x00))
        val frame = bytesOf(0xDD, 0x04, 0x80, 0x00, chk shr 8, chk and 0xFF, 0x77)
        assertFailsWith<JbdProtocolException> { JbdFrame.parseResponse(frame) }
    }
}
