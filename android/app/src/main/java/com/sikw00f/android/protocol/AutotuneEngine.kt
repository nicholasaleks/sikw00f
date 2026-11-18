package com.sikw00f.android.protocol

import com.sikw00f.android.LogBus

/** S-register numbers for the parameters the firmware exposes over RTI5,
 * matching the ATSx= commands used throughout core/device.py (S3=NETID,
 * S8/S9=MIN_FREQ/MAX_FREQ, S10=NUM_CHANNELS) plus the additional registers
 * RTI5 reports (S2=AIR_SPEED, S6=MAVLINK, S12=LBT_RSSI). */
private val S_REGISTER_MAP: Map<String, Int> = linkedMapOf(
    "NETID" to 3,
    "AIR_SPEED" to 2,
    "MIN_FREQ" to 8,
    "MAX_FREQ" to 9,
    "NUM_CHANNELS" to 10,
    "MAVLINK" to 6,
    "LBT_RSSI" to 12
)

private val rti5ParamPattern = Regex("S(\\d+):([A-Z0-9_]+)=([^\\r\\n]+)", RegexOption.IGNORE_CASE)

/**
 * Kotlin port of core/autotune.py, extended: the Python source's
 * `set_params`/`autotune_device` are no-op placeholders (they parse a param
 * file but never send AT commands). Here `applyParams` actually clones the
 * dumped parameters onto this radio via the ATSx=/AT&W/ATZ sequence used by
 * device.py's setters, so Dump -> Apply is a real end-to-end autotune.
 */
class AutotuneEngine(private val radio: SikRadio) {

    /** Port of `dump_params()`: disable promiscuous mode, pin to a NetID, reboot,
     * then brute-force RTI5 until a full S3:NETID parameter block is captured. */
    suspend fun dumpParams(netId: String, maxAttempts: Int = 500): List<String> {
        LogBus.i("[AUTOTUNE] Dumping params for net_id=$netId...")

        radio.rawEnterCommandMode()

        LogBus.i("[AUTOTUNE] Disabling promiscuous mode (ATS16=0)")
        radio.rawWrite("ATS16=0\r\n")
        radio.rawDelay(1000)
        logLines("[AUTOTUNE] Response after ATS16=0:", radio.rawReadAllLines())

        radio.rawWrite("ATS3=$netId\r\n")
        radio.rawDelay(1000)
        logLines("[AUTOTUNE] Response after ATS3:", radio.rawReadAllLines())

        radio.rawWrite("AT&W\r\n")
        radio.rawDelay(1000)
        logLines("[AUTOTUNE] After AT&W:", radio.rawReadAllLines())

        radio.rawWrite("ATZ\r\n")
        radio.rawDelay(1000)
        radio.rawReadAllLines()

        LogBus.i("[AUTOTUNE] Waiting for device to reboot...")
        radio.rawDelay(5000)

        radio.rawEnterCommandMode()

        val paramLines = mutableListOf<String>()
        var foundS3 = false

        LogBus.i("[AUTOTUNE] Spamming RTI5 to retrieve current parameters...")
        for (attempt in 0 until maxAttempts) {
            radio.rawWrite("RTI5\r\n")
            radio.rawDelay(50)

            val lines = radio.rawReadAllLines()
            if (lines.isNotEmpty()) {
                paramLines.addAll(lines)
                if (lines.any { it.uppercase().contains("S3:NETID") }) {
                    foundS3 = true
                    LogBus.i("[AUTOTUNE] Found 'S3:NETID' after ${attempt + 1} attempts.")
                    break
                }
            }
        }

        if (!foundS3) {
            LogBus.w("[AUTOTUNE] No S3:NETID block found after $maxAttempts attempts (try again?).")
        }

        radio.rawExitCommandMode()
        LogBus.i("[AUTOTUNE] Finished dumping parameters (${paramLines.size} lines).")
        return paramLines
    }

    /** Extracts KEY=VALUE pairs from raw RTI5 dump lines (format "S#:KEY=VALUE",
     * e.g. "S8:MIN_FREQ=915000"), ignoring the S-number and any non-matching lines. */
    fun extractParams(dumpLines: List<String>): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        for (raw in dumpLines) {
            val m = rti5ParamPattern.matchEntire(raw.trim()) ?: continue
            parsed[m.groupValues[2].uppercase()] = m.groupValues[3].trim()
        }
        LogBus.i("[AUTOTUNE] Extracted ${parsed.size} parameters from dump.")
        return parsed
    }

    /** Also accepts a plain KEY=VALUE file (one per line), mirroring the
     * Python source's `set_params` file format for a manually edited param file. */
    fun parseParamLines(lines: List<String>): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            val idx = line.indexOf('=')
            if (idx > 0) {
                parsed[line.substring(0, idx).uppercase()] = line.substring(idx + 1)
            }
        }
        return parsed
    }

    /**
     * Sends the known S-register values from [params] to this radio via
     * ATSx=value, then AT&W + ATZ to persist and reboot — the real
     * implementation of what core/autotune.py's `set_params` only stubs out.
     * Only registers present in [S_REGISTER_MAP] and in [params] are touched;
     * everything else on the radio is left alone.
     */
    suspend fun applyParams(params: Map<String, String>): List<String> {
        val toApply = S_REGISTER_MAP.filterKeys { params.containsKey(it) }
        if (toApply.isEmpty()) {
            LogBus.w("[AUTOTUNE] None of the dumped parameters map to a known S-register; nothing to apply.")
            return emptyList()
        }

        LogBus.i("[AUTOTUNE] Applying ${toApply.size} parameters to this radio: ${toApply.keys.joinToString()}")
        radio.rawEnterCommandMode()

        val applied = mutableListOf<String>()
        for ((key, sNum) in toApply) {
            val value = params.getValue(key)
            radio.rawWrite("ATS$sNum=$value\r\n")
            radio.rawDelay(300)
            logLines("[AUTOTUNE] ATS$sNum=$value ($key) response:", radio.rawReadAllLines())
            applied += "$key=$value"
        }

        // dumpParams() turned promiscuous mode off; turn it back on so the
        // radio keeps reporting NetIDs and forwarding every network's payload.
        radio.rawWrite("ATS16=1\r\n")
        radio.rawDelay(300)
        logLines("[AUTOTUNE] ATS16=1 (PROMISCUOUS_MODE) response:", radio.rawReadAllLines())

        radio.rawWrite("AT&W\r\n")
        radio.rawDelay(1000)
        logLines("[AUTOTUNE] After AT&W:", radio.rawReadAllLines())

        radio.rawWrite("ATZ\r\n")
        radio.rawDelay(2000)
        radio.rawReadAllLines()

        radio.rawExitCommandMode()
        LogBus.i("[AUTOTUNE] Applied ${applied.size} parameters and rebooted this radio.")
        return applied
    }

    /** Full clone workflow: dump a target NetID's live parameters, then apply
     * the ones we recognize to this radio. End-to-end equivalent of what
     * core/autotune.py's `autotune_device` describes but never implements. */
    suspend fun autotuneToClone(targetNetId: String, maxAttempts: Int = 500): AutotuneResult {
        val dumpLines = dumpParams(targetNetId, maxAttempts)
        val extracted = extractParams(dumpLines)
        val applied = applyParams(extracted)
        return AutotuneResult(dumpLines = dumpLines, extractedParams = extracted, appliedKeys = applied)
    }

    private fun logLines(prefix: String, lines: List<String>) {
        if (lines.isEmpty()) {
            LogBus.i("$prefix [no lines]")
        } else {
            LogBus.i("$prefix\n${lines.joinToString("\n") { "    $it" }}")
        }
    }
}
