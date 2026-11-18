package com.sikw00f.android.protocol

import com.sikw00f.android.LogBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val netIdPattern = Regex("NetID:\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val chanPattern = Regex("Channel:\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val paramPattern = SIK_PARAM_PATTERN

/**
 * Drone/NetID detection in promiscuous mode: a passive listener (which also
 * decodes MAVLink and GPS from the forwarded payload) and an active deep scan
 * that pulls the target's radio parameters over RTI5.
 */
class ScanEngine(private val radio: SikRadio) {


    /**
     * Pure listener: sends **no** AT commands. Requires promiscuous mode
     * (S16=1). Decodes the raw stream with [PromiscuousDecoder], so each
     * packet is credited to the NetID header that precedes it and MAVLink in
     * the payload is decoded too, and every message is passed to [onMavlink] with its sender ids.
     *
     * [initial] seeds the drone list, so a scan resumed after a lock keeps
     * what was already found.
     */
    fun passiveScan(
        stopOnDetect: Boolean,
        scanTimeoutMs: Long = 0,
        initial: Map<Int, DiscoveredDrone> = emptyMap(),
        /** Whether this radio currently has ECC on; tags newly heard networks. */
        eccMode: Boolean = false,
        onMavlink: (netId: Int?, sysId: Int, compId: Int, payload: Any) -> Unit = { _, _, _, _ -> }
    ): Flow<Map<Int, DiscoveredDrone>> = flow {
        LogBus.i("Starting passive scan (listen-only; radio must already have S16=1 set).")
        val drones = LinkedHashMap(initial)
        val decoder = PromiscuousDecoder()
        val start = System.currentTimeMillis()

        while (true) {
            if (scanTimeoutMs > 0 && System.currentTimeMillis() - start >= scanTimeoutMs) {
                LogBus.i("Reached scan_timeout -> stopping passive scan.")
                break
            }

            val bytes = radio.rawReadBytes()
            if (bytes.isEmpty()) {
                delay(50)
                continue
            }

            var changed = false
            var detected = false
            val now = System.currentTimeMillis()
            for (event in decoder.feed(bytes)) {
                when (event) {
                    is PromiscuousDecoder.Event.Packet -> {
                        if (event.netId !in drones) {
                            LogBus.d("[SCAN] Found NetID: ${event.netId}")
                            drones[event.netId] = DiscoveredDrone(
                                netId = event.netId,
                                ecc = eccMode,
                                params = mapOf("ECC" to if (eccMode) "1" else "0")
                            )
                            changed = true
                        }
                    }
                    is PromiscuousDecoder.Event.Channel -> {
                        val id = event.netId ?: continue
                        val d = drones[id] ?: continue
                        drones[id] = d.copy(
                            packetsCount = d.packetsCount + 1,
                            lastChannel = event.channel,
                            channelHits = d.channelHits + (event.channel to ((d.channelHits[event.channel] ?: 0) + 1)),
                            lastSeen = now
                        )
                        changed = true
                        if (stopOnDetect) {
                            LogBus.i("[SCAN] Stopping on detection of NetID $id")
                            detected = true
                        }
                    }
                    is PromiscuousDecoder.Event.Rssi -> {
                        val id = event.netId ?: continue
                        val d = drones[id] ?: continue
                        val dbm = rssiToDbm(event.raw)
                        drones[id] = d.copy(
                            rssiDbm = dbm,
                            rssiFast = d.rssiFast?.let { it + (dbm - it) * 0.35 } ?: dbm.toDouble(),
                            rssiSlow = d.rssiSlow?.let { it + (dbm - it) * 0.04 } ?: dbm.toDouble(),
                            rssiPeakDbm = maxOf(d.rssiPeakDbm ?: dbm, dbm)
                        )
                        changed = true
                    }
                    is PromiscuousDecoder.Event.Payload -> {
                        val id = event.netId ?: continue
                        val d = drones[id] ?: continue
                        drones[id] = d.copy(payloadBytes = d.payloadBytes + event.bytes)
                    }
                    is PromiscuousDecoder.Event.Mavlink -> {
                        onMavlink(event.netId, event.sysId, event.compId, event.payload)
                        val id = event.netId ?: continue
                        val d = drones[id] ?: continue
                        drones[id] = d.copy(mavlinkCount = d.mavlinkCount + 1)
                        changed = true
                    }
                }
            }

            if (changed) emit(LinkedHashMap(drones))
            if (detected) break
        }
        LogBus.i("Passive scan stopped.")
    }

    /**
     * Port of `deep_scan_for_drones()`. Enters AT mode, sets S16=1, then spams
     * RTI5 to pull live NetID / channel / parameter lines without rebooting.
     */
    fun deepScan(stopOnDetect: Boolean, scanTimeoutMs: Long = 0): Flow<Map<Int, DiscoveredDrone>> = flow {
        LogBus.i("Starting deep scan (AT mode, spamming RTI5 for live params).")
        val drones = LinkedHashMap<Int, DiscoveredDrone>()
        val ephemeralSet = HashSet<Int>()
        var currentNet: Int? = null

        try {
            enableScanningMode()
            val start = System.currentTimeMillis()

            while (true) {
                if (scanTimeoutMs > 0 && System.currentTimeMillis() - start >= scanTimeoutMs) {
                    LogBus.i("Reached scan_timeout -> stopping deep scan.")
                    break
                }

                radio.rawWrite("RTI5\r\n")
                radio.rawDelay(50)
                val lines = radio.rawReadAllLines()
                if (lines.isEmpty()) {
                    radio.rawDelay(50)
                    continue
                }

                var detected = false
                for (line in lines) {
                    val pMatch = paramPattern.matchEntire(line)
                    if (pMatch != null) {
                        val key = pMatch.groupValues[2].uppercase()
                        val value = pMatch.groupValues[3].trim()
                        LogBus.d("[PARAM] S${pMatch.groupValues[1]}:$key=$value")
                        val lastNet = currentNet ?: drones.keys.lastOrNull()
                        if (lastNet != null) {
                            val d = drones.getValue(lastNet)
                            drones[lastNet] = d.copy(params = d.params + (key to value))
                        }
                        continue
                    }

                    val netMatch = netIdPattern.find(line)
                    if (netMatch != null) {
                        val netVal = netMatch.groupValues[1].toIntOrNull()
                        if (netVal != null) currentNet = netVal
                        if (netVal != null && !drones.containsKey(netVal)) {
                            LogBus.i("New NetID => $netVal, ephemeral S3 set (no reboot).")
                            drones[netVal] = DiscoveredDrone(netId = netVal)
                        }
                        if (netVal != null && netVal !in ephemeralSet) {
                            ephemeralSetS3(netVal)
                            ephemeralSet.add(netVal)
                        }
                        continue
                    }

                    val chMatch = chanPattern.find(line)
                    if (chMatch != null) {
                        val chVal = chMatch.groupValues[1].toIntOrNull()
                        val lastNet = currentNet ?: drones.keys.lastOrNull()
                        if (chVal != null && lastNet != null) {
                            val d = drones.getValue(lastNet)
                            drones[lastNet] = d.copy(
                                packetsCount = d.packetsCount + 1,
                                lastChannel = chVal,
                                channelHits = d.channelHits + (chVal to ((d.channelHits[chVal] ?: 0) + 1)),
                                lastSeen = System.currentTimeMillis()
                            )
                            if (stopOnDetect) detected = true
                        }
                        continue
                    }

                    LogBus.d("[OTHER] $line")
                }

                emit(LinkedHashMap(drones))
                if (detected) break
                radio.rawDelay(20)
            }
        } finally {
            radio.rawExitCommandMode()
            LogBus.i("Deep scan stopped; exited AT mode.")
        }
    }

    /**
     * Enters AT mode for the RTI5 loop. Promiscuous mode itself is enabled,
     * saved and rebooted into before the scan starts (AppViewModel), since a
     * runtime-only ATS16=1 leaves the boot-time header filter in place; the
     * ATS16=1 here just re-asserts the flag.
     */
    private suspend fun enableScanningMode() {
        LogBus.i("Entering AT mode for deep scan (ATS16=1 re-asserted; already saved before the scan).")
        radio.rawEnterCommandMode()
        radio.rawWrite("ATS16=1\r\n")
        radio.rawDelay(1000)
        val lines = radio.rawReadAllLines()
        LogBus.i("Response to ATS16=1:\n${lines.joinToString("\n")}")
    }

    private suspend fun ephemeralSetS3(netId: Int) {
        radio.rawWrite("ATS3=$netId\r\n")
        radio.rawDelay(200)
        val lines = radio.rawReadAllLines()
        if (lines.isNotEmpty()) {
            LogBus.i("[EPHEMERAL S3] after ATS3=$netId:\n${lines.joinToString("\n")}")
        }
    }
}
