package com.sikw00f.android.protocol

import java.util.Locale

private const val MAX_FREQ_CHANNELS = 50
private const val UINT32_MASK = 0xFFFFFFFFL

/**
 * A SiK radio's hop table: channel n is tuned to `baseHz + n * stepHz`.
 *
 * The "Channel: N" lines printed in promiscuous mode are physical channel
 * indexes (`channel_map[...]`) on the *receiving* radio, so this plan must be
 * built from that radio's own ATI5 parameters to turn a hit into a frequency.
 */
data class FrequencyPlan(val baseHz: Long, val stepHz: Long, val channels: Int) {

    fun channelHz(channel: Int): Long = baseHz + channel.toLong() * stepHz

    val lowHz: Long get() = channelHz(0)
    val highHz: Long get() = channelHz(channels - 1)

    companion object {
        /** Builds the plan from ATI5 lines like `S8:MIN_FREQ=915000`; null if a field is missing. */
        fun fromParams(params: Map<String, String>): FrequencyPlan? {
            val minKHz = params["MIN_FREQ"]?.trim()?.toLongOrNull() ?: return null
            val maxKHz = params["MAX_FREQ"]?.trim()?.toLongOrNull() ?: return null
            val channels = params["NUM_CHANNELS"]?.trim()?.toIntOrNull() ?: return null
            val netId = params["NETID"]?.trim()?.toIntOrNull() ?: return null
            return compute(minKHz * 1000, maxKHz * 1000, channels, netId)
        }

        /**
         * Mirrors the firmware: radio/main.c `radio_init()` (spacing, half-step
         * guard band, NetID-seeded offset) and radio_443x.c
         * `radio_set_frequency()` (hop step rounded to 10 kHz register units).
         */
        fun compute(freqMinHz: Long, freqMaxHz: Long, numChannels: Int, netId: Int): FrequencyPlan? {
            val maxHz = if (freqMaxHz == freqMinHz) freqMinHz + 1_000_000 else freqMaxHz
            val channels = numChannels.coerceIn(1, MAX_FREQ_CHANNELS)
            val spacing = (maxHz - freqMinHz) / (channels + 2)
            if (spacing <= 0) return null

            var base = freqMinHz + spacing / 2
            if (channels > 5) {
                // `(unsigned long)(r_rand()*625)`: on the 8051, int is 16-bit, so
                // the product wraps to a signed 16-bit value and is then
                // sign-extended to uint32 before the modulo (verified in main.rst).
                val product = (sikRand(netId) * 625).toShort().toLong() and UINT32_MASK
                base += product % spacing
            }

            val stepHz = ((spacing + 5_000) / 10_000) * 10_000
            return FrequencyPlan(base, stepHz, channels)
        }

        /** First output of freq_hopping.c `r_rand()` after `r_srand(seed)` (sdcc 3.0 LCG). */
        private fun sikRand(seed: Int): Int {
            val next = ((seed.toLong() and 0xFFFF) * 1_103_515_245L + 12_345L) and UINT32_MASK
            return ((next ushr 16) % 32_768L).toInt()
        }
    }
}

fun formatMHz(hz: Long, decimals: Int = 3): String =
    String.format(Locale.US, "%.${decimals}f", hz / 1_000_000.0)

/** ATI5 / RTI5 parameter line, e.g. `S3:NETID=25`. */
val SIK_PARAM_PATTERN = Regex("S(\\d+):([A-Z0-9_]+)=([^\\r\\n]+)", RegexOption.IGNORE_CASE)

fun parseSikParams(text: String): Map<String, String> =
    SIK_PARAM_PATTERN.findAll(text).associate { it.groupValues[2].uppercase() to it.groupValues[3].trim() }

/** ATI5 name -> S-register number, e.g. NETID -> 3. */
fun parseSikRegisters(text: String): Map<String, Int> =
    SIK_PARAM_PATTERN.findAll(text).associate { it.groupValues[2].uppercase() to it.groupValues[1].toInt() }
