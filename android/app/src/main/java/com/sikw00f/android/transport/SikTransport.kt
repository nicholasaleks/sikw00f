package com.sikw00f.android.transport

/**
 * Byte pipe to a SiK radio's UART. Implemented by [UsbSerialTransport]: a direct
 * USB-OTG host connection to the radio's USB-serial adapter (FTDI/CP210x/CH340/PL2303).
 */
interface SikTransport {
    val label: String
    val isOpen: Boolean

    /** Invoked (from any thread) when the link drops on its own, e.g. the radio is unplugged. */
    var onConnectionLost: (() -> Unit)?

    suspend fun open(): Result<Unit>
    suspend fun close()

    /** Writes raw bytes to the radio. */
    suspend fun write(data: ByteArray)

    /**
     * Returns whatever bytes are immediately available (non-blocking-ish; a
     * short bounded wait is fine), or an empty array if none. Mirrors the
     * `_read_all` / `_read_all_lines` polling style used throughout the
     * Python core.
     */
    suspend fun readAvailable(): ByteArray

    /**
     * Switches the underlying UART baud rate at runtime, mirroring
     * uploader.py's `setBaudrate()` (used to hop from the AT command baud to
     * the bootloader's fixed 115200 during AT&UPDATE). Returns true if the
     * switch was actually applied; callers should warn rather than fail
     * outright when it returns false.
     */
    suspend fun setBaudRate(baud: Int): Boolean = false
}

suspend fun SikTransport.writeAscii(command: String) {
    write(command.toByteArray(Charsets.US_ASCII))
}
