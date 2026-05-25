package com.meshovik

class BleReassembler {

    private var buffer = ByteArray(0)

    fun onChunk(chunk: ByteArray): ByteArray? {
        buffer += chunk

        // Ждём заголовок
        if (buffer.size < 14) return null

        val sizeBytes = buffer.copyOfRange(10, 14)
        val payloadSize = bytesToInt(sizeBytes)

        val fullSize = 14 + payloadSize

        if (buffer.size < fullSize) return null

        val payload = buffer.copyOfRange(14, fullSize)

        // ❗ НЕ очищаем весь буфер — только обработанную часть
        buffer = buffer.copyOfRange(fullSize, buffer.size)

        return payload
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}
fun intToBytes(value: Int): ByteArray {
    return byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

fun bytesToInt(bytes: ByteArray): Int {
    return (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
}