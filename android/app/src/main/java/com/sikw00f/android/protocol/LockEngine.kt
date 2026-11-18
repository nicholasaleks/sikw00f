package com.sikw00f.android.protocol

import com.sikw00f.android.LogBus

/** Target settings a lock copies when known (from a deep scan), with their S-registers. */
private val LOCK_REGISTERS: Map<String, Int> = linkedMapOf(
    "AIR_SPEED" to 2,
    "ECC" to 5, // golay framing must match the target's or nothing decodes
    "MIN_FREQ" to 8,
    "MAX_FREQ" to 9,
    "NUM_CHANNELS" to 10
)

/**
 * Quick lock: points this radio's hop sequence at a target NetID in a single
 * AT session and one reboot, staying in promiscuous mode.
 *
 * The hop order (freq_hopping.c shuffle) and the NetID frequency offset
 * (main.c radio_init) are both seeded from NETID at boot, so matching NETID
 * (plus air speed / band / channel count when they differ) makes this radio
 * hop in step with the target. Promiscuous mode keeps the NetID headers and
 * payload flowing to serial, so the same stream decoder used for scanning
 * then sees the target continuously instead of on occasional channel overlaps.
 *
 * Compared with the full clone (AutotuneEngine), this skips the reboot +
 * RTI5 dump and the second AT session, and waits for each OK instead of
 * fixed delays: ~3–4 s instead of ~40 s+.
 */
class LockEngine(private val radio: SikRadio) {

    /** Returns the parameter values written, for updating the cached local params. */
    suspend fun quickLock(netId: Int, targetParams: Map<String, String>): Map<String, String> {
        val start = System.currentTimeMillis()
        LogBus.i("[LOCK] Quick lock -> NetID $netId")
        check(radio.fastEnterCommandMode()) { "Radio didn't enter AT command mode" }

        val written = linkedMapOf("NETID" to netId.toString())
        radio.atCommand("ATS3=$netId")
        for ((key, register) in LOCK_REGISTERS) {
            val value = targetParams[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (radio.atCommand("ATS$register=$value")) written[key] = value
        }
        radio.atCommand("ATS16=1")
        written["PROMISCUOUS_MODE"] = "1"
        radio.atCommand("AT&W", timeoutMs = 1500)
        radio.rawWrite("ATZ\r\n") // reboots straight back into data mode; no OK
        radio.rawDelay(100)

        LogBus.i(
            "[LOCK] Wrote ${written.keys.joinToString()} and rebooted " +
                "(${System.currentTimeMillis() - start} ms). Listening for NetID $netId..."
        )
        return written
    }
}
