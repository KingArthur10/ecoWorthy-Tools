package app.batterycommander.protocol.jbd

/**
 * Reassembles JBD response frames from BLE notifications, which split a frame across
 * several packets (typically 20 bytes each).
 *
 * Bytes before a start marker are discarded. A frame is emitted once its declared length is
 * complete, whether or not it is valid; validation is [JbdFrame.parseResponse]'s job.
 */
class JbdFrameAssembler(private val maxFrameSize: Int = 512) {
    private val buffer = ArrayList<Byte>()

    /** Appends a notification chunk and returns any frames it completes. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer.addAll(chunk.asList())
        val frames = mutableListOf<ByteArray>()
        while (true) {
            val start = buffer.indexOfFirst { (it.toInt() and 0xFF) == JbdFrame.START }
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.subList(0, start).clear()
            if (buffer.size < 4) break

            val total = (buffer[3].toInt() and 0xFF) + JbdFrame.OVERHEAD
            if (total > maxFrameSize) {
                // Not a plausible frame; drop this start byte and resync.
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < total) break

            frames += buffer.subList(0, total).toByteArray()
            buffer.subList(0, total).clear()
        }
        return frames
    }

    fun reset() = buffer.clear()
}
