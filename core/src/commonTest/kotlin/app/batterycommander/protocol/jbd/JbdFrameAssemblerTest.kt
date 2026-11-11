package app.batterycommander.protocol.jbd

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JbdFrameAssemblerTest {
    private val basic = Captures.all[0].basic.hexToBytes()
    private val cells = Captures.all[0].cells.hexToBytes()

    @Test
    fun reassemblesTwentyBytePackets() {
        val assembler = JbdFrameAssembler()
        val chunks = basic.asList().chunked(20).map { it.toByteArray() }
        val out = chunks.flatMap { assembler.feed(it) }
        assertEquals(1, out.size)
        assertContentEquals(basic, out[0])
    }

    @Test
    fun reassemblesSingleBytePackets() {
        val assembler = JbdFrameAssembler()
        val out = basic.flatMap { assembler.feed(byteArrayOf(it)) }
        assertContentEquals(basic, out.single())
    }

    @Test
    fun splitsTwoFramesInOneChunk() {
        val out = JbdFrameAssembler().feed(basic + cells)
        assertEquals(2, out.size)
        assertContentEquals(basic, out[0])
        assertContentEquals(cells, out[1])
    }

    @Test
    fun discardsLeadingGarbage() {
        val out = JbdFrameAssembler().feed(byteArrayOf(0x01, 0x02, 0x77) + cells)
        assertContentEquals(cells, out.single())
    }

    @Test
    fun resyncsAfterImplausibleLength() {
        val assembler = JbdFrameAssembler(maxFrameSize = 64)
        // A stray start byte followed by a length of 0xFF must not swallow the real frame.
        val out = assembler.feed(byteArrayOf(0xDD.toByte(), 0x04, 0x00, 0xFF.toByte()) + cells)
        assertContentEquals(cells, out.single())
    }

    @Test
    fun holdsPartialFrameUntilComplete() {
        val assembler = JbdFrameAssembler()
        assertTrue(assembler.feed(cells.copyOfRange(0, 6)).isEmpty())
        assertContentEquals(cells, assembler.feed(cells.copyOfRange(6, cells.size)).single())
    }
}
