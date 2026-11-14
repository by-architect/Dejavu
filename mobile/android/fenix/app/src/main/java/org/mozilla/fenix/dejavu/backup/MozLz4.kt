/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

/**
 * Reads Firefox's mozlz4 files, like Zen's zen-sessions.jsonlz4: the 8 bytes "mozLz40\0", the size of the content as
 * a little-endian 32-bit number, then the content as one LZ4 block, without LZ4's frame and checksums.
 */
internal object MozLz4 {
    /** Content larger than this is not read. */
    const val MAX_SIZE = 64 * 1024 * 1024

    private val MAGIC = "mozLz40\u0000".toByteArray(Charsets.US_ASCII)
    private const val SIZE_BYTES = 4
    private const val HEADER_SIZE = 12
    private const val MIN_MATCH = 4
    private const val NIBBLE = 4
    private const val LENGTH_MASK = 0x0F
    private const val MORE_LENGTH = 0xFF
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** Whether [bytes] start like a mozlz4 file. */
    fun isMozLz4(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /** The content of the mozlz4 file [bytes], or `null` when it is not one, is damaged or holds too much. */
    fun decompress(bytes: ByteArray): ByteArray? {
        if (!isMozLz4(bytes)) return null
        var size = 0L
        for (index in SIZE_BYTES - 1 downTo 0) {
            size = (size shl BITS_PER_BYTE) or (bytes[MAGIC.size + index].toLong() and BYTE_MASK.toLong())
        }
        if (size > MAX_SIZE) return null
        return decompressBlock(bytes, HEADER_SIZE, size.toInt())
    }

    /**
     * Decompresses the LZ4 block that runs from [start] to the end of [input], into at most [maxSize] bytes. Every
     * length and offset is checked against what is left, so damaged data gives `null` rather than reading or writing
     * out of bounds.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun decompressBlock(input: ByteArray, start: Int, maxSize: Int): ByteArray? {
        if (start < 0 || start > input.size || maxSize < 0) return null
        val output = ByteArray(maxSize)
        var source = start
        var target = 0
        while (source < input.size) {
            val token = input[source++].toInt() and BYTE_MASK
            var literals = token ushr NIBBLE
            if (literals == LENGTH_MASK) {
                do {
                    if (source >= input.size) return null
                    val more = input[source++].toInt() and BYTE_MASK
                    literals += more
                    if (literals > maxSize) return null
                } while (more == MORE_LENGTH)
            }
            if (literals > input.size - source || literals > maxSize - target) return null
            System.arraycopy(input, source, output, target, literals)
            source += literals
            target += literals
            // The last sequence of a block has its literals only.
            if (source == input.size) break

            if (input.size - source < 2) return null
            val offset = (input[source].toInt() and BYTE_MASK) or
                ((input[source + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE)
            source += 2
            if (offset == 0 || offset > target) return null
            var match = token and LENGTH_MASK
            if (match == LENGTH_MASK) {
                do {
                    if (source >= input.size) return null
                    val more = input[source++].toInt() and BYTE_MASK
                    match += more
                    if (match > maxSize) return null
                } while (more == MORE_LENGTH)
            }
            match += MIN_MATCH
            if (match > maxSize - target) return null
            // A match may overlap the bytes it writes, so it is copied one byte at a time.
            var from = target - offset
            repeat(match) { output[target++] = output[from++] }
        }
        return if (target == output.size) output else output.copyOf(target)
    }
}
