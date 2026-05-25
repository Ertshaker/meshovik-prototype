package com.meshovik

class BleChunker(private val mtu: Int = 20) {
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
}
