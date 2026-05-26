package com.meshovik

class BleReassembler {

    private val buffers = mutableMapOf<String, ByteArray>()

    fun onChunk(deviceId: String, chunk: ByteArray): ByteArray? {
        val current = buffers[deviceId] ?: ByteArray(0)
        val newBuffer = current + chunk
        buffers[deviceId] = newBuffer

        if (newBuffer.size < 14) return null

        val sizeBytes = newBuffer.copyOfRange(10, 14)
        val payloadSize = bytesToInt(sizeBytes)

        // ❗ защита от мусора
        if (payloadSize <= 0 || payloadSize > 10_000) {
            buffers[deviceId] = ByteArray(0)
            return null
        }

        val fullSize = 14 + payloadSize

        if (newBuffer.size < fullSize) return null

        val payload = newBuffer.copyOfRange(14, fullSize)

        buffers[deviceId] = newBuffer.copyOfRange(fullSize, newBuffer.size)

        return payload
    }

    fun reset(deviceId: String) {
        buffers.remove(deviceId)
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