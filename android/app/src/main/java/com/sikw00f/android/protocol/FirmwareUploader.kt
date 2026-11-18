package com.sikw00f.android.protocol

import com.sikw00f.android.LogBus
import com.sikw00f.android.transport.SikTransport
import kotlinx.coroutines.delay

/** One step of a flash operation, for driving a progress UI. */
sealed class FlashEvent {
    data class Stage(val message: String) : FlashEvent()
    data class Progress(val stage: String, val count: Int, val total: Int) : FlashEvent()
    data class Failed(val message: String) : FlashEvent()
    data object Done : FlashEvent()
}

/**
 * Kotlin port of the bootloader protocol in SiK/Firmware/tools/uploader.py's
 * `Uploader` class. This is a simple binary serial protocol (no compiler
 * involved) — sync, chip-erase, program, verify, reboot — so it ports
 * directly onto [SikTransport], unlike the `make`/`sdcc` build step, which
 * stays a desktop-only task.
 */
class FirmwareUploader(
    private val transport: SikTransport,
    private val atBaudRate: Int = 57600
) {
    companion object {
        private const val NOP = 0x00
        private const val OK = 0x10
        private const val INSYNC = 0x12
        private const val EOC = 0x20
        private const val GET_SYNC = 0x21
        private const val GET_DEVICE = 0x22
        private const val CHIP_ERASE = 0x23
        private const val LOAD_ADDRESS = 0x24
        private const val PROG_FLASH = 0x25
        private const val PROG_MULTI = 0x27
        private const val READ_MULTI = 0x28
        private const val PARAM_ERASE = 0x29
        private const val REBOOT = 0x30

        private const val PROG_MULTI_MAX = 32
        private const val READ_MULTI_MAX = 255
    }

    private val inbox = ArrayDeque<Int>()
    private var bankProgramming = -1

    // -----------------------------------------------------------------
    // Byte-level I/O, equivalent to pyserial's blocking read(1)/write()
    // -----------------------------------------------------------------

    private suspend fun fillAtLeastOne(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inbox.isEmpty() && System.currentTimeMillis() < deadline) {
            val bytes = transport.readAvailable()
            if (bytes.isNotEmpty()) {
                for (b in bytes) inbox.addLast(b.toInt() and 0xFF)
            } else {
                delay(5)
            }
        }
    }

    private suspend fun recv(timeoutMs: Long = 3000): Int {
        fillAtLeastOne(timeoutMs)
        return inbox.removeFirstOrNull() ?: throw RuntimeException("timeout waiting for data")
    }

    private suspend fun flushInput() {
        // Best-effort drain of whatever has already arrived.
        inbox.clear()
        repeat(3) {
            val bytes = transport.readAvailable()
            for (b in bytes) inbox.addLast(b.toInt() and 0xFF)
        }
        inbox.clear()
    }

    private suspend fun send(vararg bytes: Int) {
        transport.write(ByteArray(bytes.size) { bytes[it].toByte() })
    }

    private suspend fun send(bytes: List<Int>) {
        transport.write(ByteArray(bytes.size) { bytes[it].toByte() })
    }

    // -----------------------------------------------------------------
    // Protocol primitives (1:1 with Uploader's private methods)
    // -----------------------------------------------------------------

    private suspend fun getSync(timeoutMs: Long = 3000) {
        val c1 = recv(timeoutMs)
        if (c1 != INSYNC) throw RuntimeException("unexpected 0x${c1.toString(16)} instead of INSYNC")
        val c2 = recv(timeoutMs)
        if (c2 != OK) throw RuntimeException("unexpected 0x${c2.toString(16)} instead of OK")
    }

    private suspend fun trySync(): Boolean {
        send(List(PROG_MULTI_MAX + 2) { NOP })
        flushInput()
        send(GET_SYNC, EOC)
        return try {
            getSync(timeoutMs = 1500)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun erase(eraseParams: Boolean) {
        send(CHIP_ERASE, EOC)
        getSync()
        if (eraseParams) {
            LogBus.i("[FLASH] Resetting parameters...")
            send(PARAM_ERASE, EOC)
            getSync()
        }
    }

    private suspend fun setAddress(address: Int, banking: Boolean) {
        if (banking) {
            val bank = address shr 16
            if (bankProgramming != bank) {
                bankProgramming = bank
                LogBus.d(if (bank == 0) "[FLASH] HOME" else "[FLASH] BANK $bank")
            }
            send(LOAD_ADDRESS, address and 0xFF, (address shr 8) and 0xFF, (address shr 16) and 0xFF, EOC)
        } else {
            send(LOAD_ADDRESS, address and 0xFF, (address shr 8) and 0xFF, EOC)
        }
        getSync()
    }

    private suspend fun programMulti(data: List<Int>) {
        var syncCount = 0
        var remaining = data
        while (remaining.isNotEmpty()) {
            val n = minOf(remaining.size, PROG_MULTI_MAX)
            val block = remaining.subList(0, n)
            remaining = remaining.subList(n, remaining.size)
            send(PROG_MULTI, n)
            send(block)
            send(EOC)
            syncCount++
        }
        repeat(syncCount) { getSync() }
    }

    private suspend fun verifyMulti(data: List<Int>): Boolean {
        send(READ_MULTI, data.size, EOC)
        for (expected in data) {
            if (recv() != expected) return false
        }
        getSync()
        return true
    }

    private suspend fun rebootToApp() {
        send(REBOOT)
    }

    private suspend fun identify(): Pair<Int, Int> {
        send(GET_DEVICE, EOC)
        val boardId = recv()
        val boardFreq = recv()
        getSync()
        return boardId to boardFreq
    }

    /** Port of `autosync()`: wakes the running firmware with AT&UPDATE so it
     * drops into the bootloader, which then listens at a fixed 115200 baud. */
    private suspend fun autosync(): Boolean {
        if (atBaudRate != 115200) {
            if (!transport.setBaudRate(atBaudRate)) {
                LogBus.w("[FLASH] This transport can't switch baud rate; AT&UPDATE handshake may not work (USB-OTG is required for flashing).")
            }
        }
        LogBus.i("[FLASH] Trying AT&UPDATE handshake...")
        transport.write("\r\n".toByteArray())
        delay(1000)
        transport.write("+++".toByteArray())
        delay(1100)
        transport.write("\r\nATI\r\n".toByteArray())
        delay(500)
        transport.readAvailable()
        transport.write("\r\n".toByteArray())
        delay(200)
        transport.write("AT&UPDATE\r\n".toByteArray())
        delay(700)
        transport.readAvailable()
        if (atBaudRate != 115200) transport.setBaudRate(115200)
        LogBus.i("[FLASH] Sent AT&UPDATE.")
        return true
    }

    /** Port of `check()`: establish contact with the bootloader, nudging the
     * radio out of app mode via AT&UPDATE if it isn't already listening. */
    suspend fun checkBootloader(): Boolean {
        repeat(3) {
            try {
                if (trySync()) return true
            } catch (_: Exception) {
            }
            autosync()
        }
        return trySync()
    }

    /**
     * Port of `upload()`: erase, program, verify, reboot. Emits [FlashEvent]s
     * for a progress UI. Throws on failure (verification mismatch, sync loss).
     */
    suspend fun upload(firmware: IntelHexFirmware, eraseParams: Boolean, onEvent: suspend (FlashEvent) -> Unit) {
        if (!checkBootloader()) {
            onEvent(FlashEvent.Failed("Failed to contact the bootloader. Is the radio connected and powered?"))
            return
        }

        val (boardId, _) = identify()
        if (firmware.bankingDetected && (boardId and 0x80) == 0) {
            onEvent(FlashEvent.Failed("This firmware requires a CPU with banking support, which this board doesn't have."))
            return
        }
        if ((boardId and 0x80) == 0x80) {
            firmware.bankingDetected = true
        }

        onEvent(FlashEvent.Stage("Erasing..."))
        erase(eraseParams)

        onEvent(FlashEvent.Stage("Programming..."))
        val code = firmware.code()
        val total = firmware.totalSize()
        var count = 0
        for (address in code.keys.sorted()) {
            setAddress(address, firmware.bankingDetected)
            val bytes = code.getValue(address)
            var offset = 0
            while (offset < bytes.size) {
                val chunk = bytes.subList(offset, minOf(offset + PROG_MULTI_MAX, bytes.size))
                programMulti(chunk)
                offset += chunk.size
                count += chunk.size
                onEvent(FlashEvent.Progress("Programming", count, total))
            }
        }

        onEvent(FlashEvent.Stage("Verifying..."))
        count = 0
        for (address in code.keys.sorted()) {
            setAddress(address, firmware.bankingDetected)
            val bytes = code.getValue(address)
            var offset = 0
            while (offset < bytes.size) {
                val chunk = bytes.subList(offset, minOf(offset + READ_MULTI_MAX, bytes.size))
                if (!verifyMulti(chunk)) {
                    onEvent(FlashEvent.Failed("Verification failed at address 0x${address.toString(16)}"))
                    return
                }
                offset += chunk.size
                count += chunk.size
                onEvent(FlashEvent.Progress("Verifying", count, total))
            }
        }

        rebootToApp()
        onEvent(FlashEvent.Done)
    }
}
