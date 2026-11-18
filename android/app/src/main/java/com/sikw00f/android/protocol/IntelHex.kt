package com.sikw00f.android.protocol

/**
 * Minimal Intel HEX parser, ported from the `Firmware` class in
 * SiK/Firmware/tools/uploader.py. Only record types 0x00 (data) and 0x04
 * (extended linear address, used for banked/24-bit-addressed boards) are
 * interesting — everything else (0x01 EOF, 0x02/0x03 segment forms) is
 * ignored, matching the Python source.
 *
 * Produces a flat map of {address -> contiguous byte range}, merging
 * adjacent records the same way the Python `__insert` does, so programming
 * can walk fewer, larger blocks.
 */
class IntelHexFirmware(lines: List<String>) {

    private val ranges = LinkedHashMap<Int, MutableList<Int>>() // address -> unsigned bytes
    private var upperAddress = 0
    var bankingDetected = false
        internal set

    init {
        for (raw in lines) parseLine(raw)
    }

    fun code(): Map<Int, List<Int>> = ranges

    fun totalSize(): Int = ranges.values.sumOf { it.size }

    private fun parseLine(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty() || line[0] != ':') return

        // Strip leading ':' and trailing checksum byte (2 hex chars).
        val hex = line.substring(1, line.length - 2)
        val bin = hexToBytes(hex)
        if (bin.size < 4) return
        val command = bin[3].toInt() and 0xFF

        when {
            command == 4 && (bin[0].toInt() and 0xFF) == 2 && (((bin[1].toInt() and 0xFF) shl 8) or (bin[2].toInt() and 0xFF)) == 0x0000 -> {
                upperAddress = ((bin[4].toInt() and 0xFF) shl 8) or (bin[5].toInt() and 0xFF)
                bankingDetected = true
            }
            command == 0 -> {
                val address = (((bin[1].toInt() and 0xFF) shl 8) or (bin[2].toInt() and 0xFF)) or (upperAddress shl 16)
                val dataBytes = bin.drop(4).map { it.toInt() and 0xFF }
                insert(address, dataBytes)
            }
            else -> Unit
        }
    }

    private fun insert(address: Int, bytes: List<Int>) {
        // Merge with a range that immediately follows this one.
        val candidateAfter = address + bytes.size
        val following = ranges.remove(candidateAfter)
        val merged = bytes.toMutableList()
        if (following != null) merged.addAll(following)

        // Merge with a range that immediately precedes this one.
        for ((candidate, existing) in ranges) {
            if (candidate + existing.size == address) {
                existing.addAll(merged)
                return
            }
        }
        ranges[address] = merged
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) hex.dropLast(1) else hex
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
