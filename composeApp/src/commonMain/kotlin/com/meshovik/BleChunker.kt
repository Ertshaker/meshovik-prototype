package com.meshovik

class BleChunker(
    private val mtu: Int = 20
) {

    fun chunk(data: ByteArray): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var index = 0

        while (index < data.size) {
            val end = (index + mtu).coerceAtMost(data.size)
            chunks.add(data.copyOfRange(index, end))
            index = end
        }

        return chunks
    }

    suspend fun BleDevice.writeChunked(data: ByteArray) {
        val chunker = BleChunker(mtu = 20)

        chunker.chunk(data).forEach { chunk ->
            write(chunk)
        }
    }
}
