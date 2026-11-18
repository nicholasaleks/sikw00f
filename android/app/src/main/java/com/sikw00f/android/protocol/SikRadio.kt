package com.sikw00f.android.protocol

import com.sikw00f.android.LogBus
import com.sikw00f.android.transport.SikTransport
import com.sikw00f.android.transport.writeAscii
import kotlinx.coroutines.delay

/**
 * Kotlin port of the AT-command sequences in core/device.py, core/autotune.py
 * and core/scan.py — everything except firmware flashing (`flash_device`,
 * which clones/builds/uploads via git+make+sdcc and only makes sense on a
 * desktop toolchain) and MAVLink eavesdropping (core/eavesdrop.py).
 *
 * Every public function here is a 1:1 behavioral match for its Python
 * counterpart: same AT command sequence, same ordering of AT&W / ATZ / ATO,
 * same rough timing. The transport (USB-OTG) is abstracted
 * away via [SikTransport].
 */
class SikRadio(private val transport: SikTransport) {

    private val buffer = StringBuilder()

    // ---------------------------------------------------------------------
    // Low-level helpers (mirror _read_all / _read_all_lines in the Python core)
    // ---------------------------------------------------------------------

    /** Drains whatever bytes are currently available into the line buffer. */
    private suspend fun pump() {
        val bytes = transport.readAvailable()
        if (bytes.isNotEmpty()) {
            buffer.append(String(bytes, Charsets.ISO_8859_1))
        }
    }

    /** Non-blocking: returns + clears whatever full/partial text has accumulated so far. */
    private suspend fun readAll(): String {
        pump()
        val text = buffer.toString()
        buffer.clear()
        return text
    }

    /** Polls for up to [timeoutMs], accumulating bytes, mirroring the Python helpers'
     *  read-until-quiet behavior used right after sending a command. */
    private suspend fun readAllSettled(timeoutMs: Long = 1000): String {
        delay(timeoutMs)
        return readAll()
    }

    /** When we last sent anything; the +++ escape needs ~1 s of host silence before it. */
    private var lastWriteAt = 0L

    private suspend fun writeCommand(command: String) {
        LogBus.d("[TX] ${command.trim()}")
        transport.writeAscii(command)
        lastWriteAt = System.currentTimeMillis()
    }

    /** Polls until a line reading exactly "OK" (true) or "ERROR" (false) arrives, or [timeoutMs] passes. */
    private suspend fun awaitOk(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val seen = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (buffer.isNotEmpty()) {
                seen.append(buffer)
                buffer.clear()
                val lines = seen.split('\n').map { it.trim() }
                if (lines.any { it == "OK" }) return true
                if (lines.any { it == "ERROR" }) return false
            }
            delay(30)
        }
        return false
    }

    private fun logResponse(prefix: String, response: String) {
        val trimmed = response.trim()
        LogBus.i(if (trimmed.isEmpty()) "$prefix [no response]" else "$prefix\n$trimmed")
    }

    // ---------------------------------------------------------------------
    // AT mode entry / exit (every device.py function starts/ends this way)
    // ---------------------------------------------------------------------

    suspend fun enterCommandMode() {
        buffer.clear()
        delay(1000)
        // Ensure we're out of any existing AT mode first.
        writeCommand("\r\n")
        delay(500)
        writeCommand("ATO\r\n")
        delay(1000)
        readAll()

        // Enter AT command mode.
        writeCommand("+++")
        delay(2000)
        readAll()
    }

    suspend fun exitCommandMode() {
        val response = run {
            writeCommand("ATO\r\n")
            readAllSettled(1000)
        }
        logResponse("Response:", response)
    }

    // ---------------------------------------------------------------------
    // Firmware check  (device.py: check_firmware_modification)
    // ---------------------------------------------------------------------

    /** This radio's parameters from the most recent ATI5 read, or null if none yet. */
    var localParams: Map<String, String>? = null
        private set(value) {
            field = value
            onParamsChanged?.invoke(value)
        }

    /** Called whenever [localParams] changes (ATI5 read or a write), so the UI can show live values. */
    var onParamsChanged: ((Map<String, String>?) -> Unit)? = null

    /** S-register numbers from the last ATI5 read (e.g. NETID -> 3). */
    private var localRegisters: Map<String, Int> = emptyMap()

    private suspend fun queryAti5(): String {
        fastEnterCommandMode() // falls back to the slow sequence itself; ATI5 is tried either way
        writeCommand("ATI5\r\n")
        val response = readAllSettled(1000)
        exitCommandMode()
        parseSikRegisters(response).takeIf { it.isNotEmpty() }?.let { localRegisters = it }
        parseSikParams(response).takeIf { it.isNotEmpty() }?.let { localParams = it }
        return response
    }

    /** Reads this radio's own parameters (ATI5), e.g. to build its [FrequencyPlan]. */
    suspend fun readLocalParams(): Map<String, String>? {
        LogBus.i("Reading local radio parameters (ATI5)...")
        queryAti5()
        return localParams
    }

    /** Returns true if ATI5 reports PROMISCUOUS_MODE, false if it doesn't, or null if the radio didn't answer. */
    suspend fun checkFirmwareModification(): Boolean? {
        LogBus.i("Checking firmware modification...")
        val response = queryAti5()

        if (response.isBlank()) {
            LogBus.e("No ATI5 response from radio.")
            return null
        }
        val modded = response.uppercase().contains("PROMISCUOUS_MODE")
        if (modded) {
            LogBus.i("Firmware mod detected (PROMISCUOUS_MODE found).")
        } else {
            LogBus.w("Firmware modification not detected — flash the modded firmware.")
        }
        return modded
    }

    // ---------------------------------------------------------------------
    // Parameter writes
    // ---------------------------------------------------------------------

    /** Whether the last ATI5 read (or a write since) shows promiscuous mode switched on; null if unknown. */
    val promiscuousEnabled: Boolean?
        get() = localParams?.get("PROMISCUOUS_MODE")?.trim()?.let { it == "1" }

    /**
     * Switches promiscuous mode on persistently. The reboot is required: the
     * firmware sets up its hardware NetID header filter once at boot
     * (radio_configure), so S16 changed at runtime only turns on the NetID
     * printouts while other networks' packets are still dropped.
     */
    internal suspend fun enablePromiscuousFast(): Boolean =
        writeParams(mapOf("PROMISCUOUS_MODE" to "1")).containsKey("PROMISCUOUS_MODE")

    /**
     * Writes [changes] (ATI5 names -> values, e.g. "NETID" to "18") in a single
     * fast AT session, then saves (AT&W) and reboots (ATZ) once. Returns the
     * values the radio accepted; empty if nothing was saved. Values the radio
     * rejects are skipped, and nothing is kept if the save itself fails.
     */
    internal suspend fun writeParams(changes: Map<String, String>): Map<String, String> {
        if (changes.isEmpty()) return emptyMap()
        LogBus.i("Writing radio params: ${changes.entries.joinToString { "${it.key}=${it.value}" }}")
        if (!fastEnterCommandMode()) {
            LogBus.e("Radio didn't enter AT command mode.")
            return emptyMap()
        }
        val accepted = linkedMapOf<String, String>()
        for ((key, raw) in changes) {
            val value = raw.trim()
            val register = registerFor(key)
            if (register == null) {
                LogBus.w("No S-register known for $key; skipped.")
                continue
            }
            if (atCommand("ATS$register=$value")) accepted[key] = value else LogBus.w("Radio rejected $key=$value")
        }
        if (accepted.isEmpty()) {
            writeCommand("ATO\r\n")
            delay(100)
            readAll()
            return emptyMap()
        }
        val saved = atCommand("AT&W", timeoutMs = 1500)
        writeCommand("ATZ\r\n") // reboot applies saved values (or reverts unsaved ones)
        delay(REBOOT_SETTLE_MS)
        readAll()
        if (!saved) {
            LogBus.e("Radio didn't confirm AT&W; changes were not saved.")
            return emptyMap()
        }
        updateLocalParams(accepted)
        LogBus.i("Saved ${accepted.keys.joinToString()} and rebooted the radio.")
        return accepted
    }

    /** S-register number for an ATI5 name, from the last ATI5 read or the SiK defaults. */
    fun registerFor(key: String): Int? = localRegisters[key.uppercase()] ?: STANDARD_REGISTERS[key.uppercase()]

    /** Reboots the radio (ATZ) without changing anything. */
    suspend fun resetDevice() {
        LogBus.i("Rebooting radio (ATZ)...")
        if (!fastEnterCommandMode()) LogBus.w("Radio didn't confirm AT mode; sending ATZ anyway.")
        writeCommand("ATZ\r\n")
        delay(REBOOT_SETTLE_MS)
        readAll()
        LogBus.i("Radio rebooted.")
    }

    // ---------------------------------------------------------------------
    // Exposed for ScanEngine / AutotuneEngine, which need lower-level control
    // over the AT session than the request/response helpers above provide
    // (e.g. spamming RTI5 without re-entering AT mode each time).
    // ---------------------------------------------------------------------

    internal suspend fun rawEnterCommandMode() = enterCommandMode()
    internal suspend fun rawExitCommandMode() = exitCommandMode()
    internal suspend fun rawWrite(command: String) = writeCommand(command)
    internal suspend fun rawReadAllLines(): List<String> {
        pump()
        val text = buffer.toString()
        if (text.isEmpty()) return emptyList()
        // Hold back a trailing partial line until the rest arrives: a USB read
        // can end mid-line, and parsing "NetID: 1" out of "NetID: 18" produced
        // phantom NetIDs. Cap the wait so a newline-free blob can't pile up.
        val lastBreak = text.lastIndexOf('\n')
        if (lastBreak < 0 && text.length < MAX_PARTIAL_LINE) return emptyList()
        val complete = if (lastBreak < 0) text else text.substring(0, lastBreak + 1)
        buffer.setLength(0)
        if (lastBreak >= 0) buffer.append(text, lastBreak + 1, text.length)
        return complete.split("\r\n", "\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Raw bytes (anything already buffered first), for binary-safe stream decoding. */
    internal suspend fun rawReadBytes(): ByteArray {
        val buffered = if (buffer.isEmpty()) null else buffer.toString().toByteArray(Charsets.ISO_8859_1)
        buffer.clear()
        val fresh = transport.readAvailable()
        return if (buffered == null) fresh else buffered + fresh
    }

    /**
     * Enters AT mode in ~2 s instead of ~4.5 s: waits out only the remaining
     * guard time, then returns as soon as the radio answers "OK". Falls back
     * to the slow, fixed-delay sequence if the radio doesn't answer.
     */
    internal suspend fun fastEnterCommandMode(): Boolean {
        val sinceWrite = System.currentTimeMillis() - lastWriteAt
        if (sinceWrite < GUARD_MS) delay(GUARD_MS - sinceWrite)
        readAll() // discard scan output
        writeCommand("+++")
        if (awaitOk(1500)) return true
        writeCommand("AT\r\n") // maybe already in AT mode
        if (awaitOk(600)) return true
        LogBus.w("Fast AT entry got no OK; retrying with the slow sequence.")
        enterCommandMode()
        writeCommand("AT\r\n")
        return awaitOk(1000)
    }

    /** Sends an AT command and waits for its OK (true), ERROR or timeout (false). */
    internal suspend fun atCommand(command: String, timeoutMs: Long = 1000): Boolean {
        writeCommand("$command\r\n")
        val ok = awaitOk(timeoutMs)
        if (!ok) LogBus.w("No OK for '$command'")
        return ok
    }

    /** Records parameter values just written to this radio (e.g. by a lock), so
     * the cached ATI5 view and the frequency plan stay in sync without re-reading. */
    internal fun updateLocalParams(changes: Map<String, String>) {
        localParams = (localParams ?: emptyMap()) + changes
    }
    internal suspend fun rawDelay(ms: Long) = delay(ms)

    private companion object {
        /** SiK parameter order (radio/parameters.h); S16 is the SiKW00F promiscuous-mode addition. */
        val STANDARD_REGISTERS = listOf(
            "FORMAT", "SERIAL_SPEED", "AIR_SPEED", "NETID", "TXPOWER", "ECC", "MAVLINK", "OPPRESEND",
            "MIN_FREQ", "MAX_FREQ", "NUM_CHANNELS", "DUTY_CYCLE", "LBT_RSSI", "MANCHESTER", "RTSCTS",
            "MAX_WINDOW", "PROMISCUOUS_MODE"
        ).withIndex().associate { (i, name) -> name to i }
        const val GUARD_MS = 1100L
        const val REBOOT_SETTLE_MS = 1500L
        const val MAX_PARTIAL_LINE = 512
    }
}
