package com

class BleReassembler {

    private var buffer = ByteArray(0)
    private var expectedSize: Int? = null

    fun onChunk(chunk: ByteArray): ByteArray? {
        buffer += chunk

        if (expectedSize == null && buffer.size >= 4) {
            expectedSize = buffer.copyOfRange(0, 4)
                .toInt()
        }

        val size = expectedSize ?: return null

        if (buffer.size >= size + 4) {
            val message = buffer.copyOfRange(4, 4 + size)

            buffer = buffer.copyOfRange(4 + size, buffer.size)
            expectedSize = null

            return message
        }

        return null
    }

    private fun ByteArray.toInt(): Int =
        (this[0].toInt() shl 24) or
                (this[1].toInt() shl 16) or
                (this[2].toInt() shl 8) or
                this[3].toInt()
}