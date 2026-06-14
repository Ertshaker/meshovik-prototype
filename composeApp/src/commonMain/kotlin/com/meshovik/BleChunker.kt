package com.meshovik

class BleChunker {

    private var currentMtu = 23                  // по умолчанию минимальный
    private var overhead = 25                    // запас на заголовки + безопасность

    val chunkSize: Int
        get() = (currentMtu - overhead).coerceAtLeast(20)

    fun updateMtu(newMtu: Int) {
        currentMtu = newMtu
    }

    fun chunk(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()

        val size = chunkSize
        val chunks = mutableListOf<ByteArray>()
        var index = 0

        while (index < data.size) {
            val end = (index + size).coerceAtMost(data.size)
            chunks.add(data.copyOfRange(index, end))
            index = end
        }

        return chunks
    }
}
