package com.sikw00f.android.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.sikw00f.android.DetectionLog
import com.sikw00f.android.LogBus
import com.sikw00f.android.LogLevel
import com.sikw00f.android.ScanService
import com.sikw00f.android.alerts.Alert
import com.sikw00f.android.alerts.AlertDispatcher
import com.sikw00f.android.alerts.AlertSettings
import com.sikw00f.android.alerts.AlertSettingsStore
import com.sikw00f.android.alerts.AlertTrigger
import com.sikw00f.android.alerts.KnownDrone
import com.sikw00f.android.alerts.KnownDroneStore
import com.sikw00f.android.alerts.TakCotSender
import com.sikw00f.android.protocol.AutotuneEngine
import com.sikw00f.android.protocol.AutotuneResult
import com.sikw00f.android.protocol.DiscoveredDrone
import com.sikw00f.android.protocol.DronePosition
import com.sikw00f.android.protocol.FirmwareUploader
import com.sikw00f.android.protocol.FlashEvent
import com.sikw00f.android.protocol.FrequencyPlan
import com.sikw00f.android.protocol.IntelHexFirmware
import com.sikw00f.android.protocol.LockEngine
import com.sikw00f.android.protocol.ScanEngine
import com.sikw00f.android.protocol.DroneIntel
import com.sikw00f.android.protocol.IntelEvent
import com.sikw00f.android.protocol.distanceM
import com.sikw00f.android.protocol.formatMHz
import com.sikw00f.android.protocol.update
import com.sikw00f.android.protocol.ScanMode
import com.sikw00f.android.protocol.ScanState
import com.sikw00f.android.protocol.SikRadio
import com.sikw00f.android.transport.SikTransport
import com.sikw00f.android.transport.UsbSerialTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val label: String, val baud: Int) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
    /** The link dropped on its own (radio unplugged / USB I/O failure). */
    data class Lost(val message: String) : ConnectionState()
}

/** Whether this radio's hop sequence is pointed at a target drone. */
sealed class LockState {
    data object None : LockState()
    data class Locking(val netId: Int) : LockState()
    data class Locked(val netId: Int) : LockState()
    data class Failed(val netId: Int, val message: String) : LockState()
}

private const val AUTO_LOCK_MIN_PACKETS = 3
/** How long after a lock to wait for the target's traffic before falling back. */
private const val LOCK_VERIFY_MS = 8_000L
/** Dwell per ECC setting while the ECC sweep alternates. */
private const val ECC_DWELL_MS = 20_000L

/**
 * Parameters a lock (quick or full) may change, saved before the first
 * change so the radio can be put back exactly as it was.
 */
private val RESTORE_KEYS = listOf(
    "NETID", "AIR_SPEED", "TXPOWER", "ECC", "MAVLINK", "OPPRESEND", "MIN_FREQ", "MAX_FREQ",
    "NUM_CHANNELS", "DUTY_CYCLE", "LBT_RSSI", "MANCHESTER", "RTSCTS", "MAX_WINDOW", "PROMISCUOUS_MODE"
)
private const val POSITION_RECORD_INTERVAL_MS = 5_000L

/** Result of the ATI5 PROMISCUOUS_MODE check shown in the Connect tab's firmware section. */
sealed class FirmwareStatus {
    data object Unknown : FirmwareStatus()
    data object Checking : FirmwareStatus()
    data object Modded : FirmwareStatus()
    data object Stock : FirmwareStatus()
    data object NoResponse : FirmwareStatus()
    data class Error(val message: String) : FirmwareStatus()
}

/** Parameters that change this radio's hop table (so the MHz mapping must be rebuilt). */
private val HOP_TABLE_KEYS = setOf("NETID", "MIN_FREQ", "MAX_FREQ", "NUM_CHANNELS")

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("radio_state", Context.MODE_PRIVATE)
    private val alertStore = AlertSettingsStore(application)
    private val alerts = AlertDispatcher(application)
    private val tak = TakCotSender()

    private val knownStore = KnownDroneStore(application)

    /** User-labelled NetIDs; friendly ones are muted for alerts and auto-lock. */
    private val _knownDrones = MutableStateFlow(knownStore.load().also { DroneColorOverrides.sync(it) })
    val knownDrones = _knownDrones.asStateFlow()

    fun saveKnownDrone(drone: KnownDrone) {
        val next = _knownDrones.value + (drone.netId to drone)
        _knownDrones.value = next
        knownStore.save(next)
        DroneColorOverrides.sync(next)
        LogBus.i("Known drone saved: ${drone.label} (NetID ${drone.netId}${if (drone.friendly) ", friendly" else ""})")
    }

    fun removeKnownDrone(netId: Int) {
        val next = _knownDrones.value - netId
        _knownDrones.value = next
        knownStore.save(next)
        DroneColorOverrides.sync(next)
    }

    private fun isFriendly(netId: Int) = _knownDrones.value[netId]?.friendly == true

    private val _alertSettings = MutableStateFlow(alertStore.load())
    val alertSettings = _alertSettings.asStateFlow()

    /** NetIDs whose first GPS fix has already raised an alert. */
    private val locatedNetIds = HashSet<Int>()

    /**
     * The radio's settings from before SiKW00F first changed them (lock, ECC
     * sweep, promiscuous mode). Persisted, so a radio unplugged while locked
     * can still be put back on the next connect. Null = nothing to restore.
     */
    private val _baseline = MutableStateFlow(loadBaseline())
    val baseline = _baseline.asStateFlow()

    private val _eccSweep = MutableStateFlow(prefs.getBoolean("ecc_sweep", false))
    val eccSweep = _eccSweep.asStateFlow()

    private var lockWatchdog: Job? = null

    /** The connected radio's own parameters (ATI5), live as they're read or written. */
    private val _radioParams = MutableStateFlow<Map<String, String>?>(null)
    val radioParams = _radioParams.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState = _scanState.asStateFlow()

    private val _firmwareStatus = MutableStateFlow<FirmwareStatus>(FirmwareStatus.Unknown)
    val firmwareStatus = _firmwareStatus.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _autotuneResult = MutableStateFlow<AutotuneResult?>(null)
    val autotuneResult = _autotuneResult.asStateFlow()

    private val _flashEvents = MutableStateFlow<List<FlashEvent>>(emptyList())
    val flashEvents = _flashEvents.asStateFlow()

    private val _flashing = MutableStateFlow(false)
    val flashing = _flashing.asStateFlow()

    /**
     * Set after a successful flash: the USB-serial link is usually left in a
     * bad state by the bootloader handoff, so the radio should be physically
     * re-plugged. Cleared on the next successful connect.
     */
    private val _replugNeeded = MutableStateFlow(false)
    val replugNeeded = _replugNeeded.asStateFlow()

    /** Everything decoded from each NetID's MAVLink (vehicle and GCS kept apart by sysid). */
    private val _intel = MutableStateFlow<Map<Int, DroneIntel>>(emptyMap())
    val intel = _intel.asStateFlow()

    /** Phone position from the map, for distances and to stamp detection records. */
    private val _phoneLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val phoneLocation = _phoneLocation.asStateFlow()

    /** NetIDs already logged as detections this scan session. */
    private val loggedNetIds = HashSet<Int>()
    private val lastPositionRecord = HashMap<Int, Long>()

    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()

    /** Last GPS fix per NetID, decoded from each drone's own MAVLink while listening. */
    private val _dronePositions = MutableStateFlow<Map<Int, DronePosition>>(emptyMap())
    val dronePositions = _dronePositions.asStateFlow()

    /** Set while the radio is being reconfigured before a scan (e.g. enabling promiscuous mode). */
    private val _radioPrep = MutableStateFlow<String?>(null)
    val radioPrep = _radioPrep.asStateFlow()

    /** Why the last scan attempt was refused (stock / unverified firmware), or null. */
    private val _scanBlocked = MutableStateFlow<String?>(null)
    val scanBlocked = _scanBlocked.asStateFlow()

    private val _lockState = MutableStateFlow<LockState>(LockState.None)
    val lockState = _lockState.asStateFlow()

    private val _autoLock = MutableStateFlow(true)
    val autoLock = _autoLock.asStateFlow()

    /** Auto-lock fires at most once per user-started scan. */
    private var autoLockArmed = false

    /** NetID the radio is locked onto (used for MAVLink seen while not in promiscuous mode). */
    private var targetNetId: Int? = null

    /** Hop table of the connected radio, used to turn reported channels into MHz. */
    private val _frequencyPlan = MutableStateFlow<FrequencyPlan?>(null)
    val frequencyPlan = _frequencyPlan.asStateFlow()

    private var transport: SikTransport? = null
    private var radio: SikRadio? = null
    private var scanEngine: ScanEngine? = null
    private var autotuneEngine: AutotuneEngine? = null
    private var lockEngine: LockEngine? = null
    private var scanJob: Job? = null

    init {
        // Foreground service + wake lock while a scan runs, so it survives screen-off and backgrounding.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(scanState, lockState) { s, l -> s to l }.collect { (s, l) ->
                if (s is ScanState.Running) {
                    val mode = if (s.mode == ScanMode.DEEP) "Deep scan" else "Passive scan"
                    val lock = (l as? LockState.Locked)?.let { " · locked on NetID ${it.netId}" } ?: ""
                    ScanService.update(getApplication(), "$mode · ${s.drones.size} NetID(s)$lock")
                } else {
                    ScanService.stop(getApplication())
                }
            }
        }
    }

    val isConnected: Boolean get() = radio != null

    fun connectUsb(context: Context, driver: UsbSerialDriver, baud: Int) {
        connect(UsbSerialTransport(context.applicationContext, driver, baud), baud)
    }

    private fun connect(newTransport: SikTransport, baud: Int) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            val result = newTransport.open()
            result.onSuccess {
                newTransport.onConnectionLost = { viewModelScope.launch { onTransportLost(newTransport) } }
                transport = newTransport
                radio = SikRadio(newTransport).also { r -> r.onParamsChanged = { _radioParams.value = it } }
                ScanService.onStopRequested = { stopScan("stopped from notification") }
                scanEngine = ScanEngine(radio!!)
                autotuneEngine = AutotuneEngine(radio!!)
                lockEngine = LockEngine(radio!!)
                _connectionState.value = ConnectionState.Connected(newTransport.label, baud)
                _replugNeeded.value = false
                LogBus.i("Connected via ${newTransport.label}")
                // One ATI5 read gives firmware status and every radio parameter for the Connect tab.
                checkFirmwareModification()
            }.onFailure { e ->
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            tearDownConnection(scanStopReason = "stopped by user")
            _connectionState.value = ConnectionState.Disconnected
            LogBus.i("Disconnected.")
        }
    }

    private suspend fun onTransportLost(lostTransport: SikTransport) {
        if (transport !== lostTransport) return // already disconnected or replaced
        tearDownConnection(scanStopReason = "radio unplugged")
        _connectionState.value = ConnectionState.Lost("Radio unplugged")
        LogBus.w("Radio connection lost; disconnected.")
    }

    private suspend fun tearDownConnection(scanStopReason: String) {
        lockWatchdog?.cancel()
        val wasScanning = scanJob != null
        stopScan(scanStopReason) // writes summaries if a scan was running
        if (!wasScanning) writeSummaries("disconnect")
        transport?.onConnectionLost = null
        transport?.close()
        transport = null
        radio = null
        scanEngine = null
        autotuneEngine = null
        lockEngine = null
        targetNetId = null
        _lockState.value = LockState.None
        _firmwareStatus.value = FirmwareStatus.Unknown
        _scanBlocked.value = null
        _frequencyPlan.value = null
        _radioParams.value = null
    }

    fun checkFirmwareModification() {
        val r = radio ?: run {
            LogBus.e("Not connected to a radio.")
            return
        }
        runBusy {
            _firmwareStatus.value = FirmwareStatus.Checking
            _firmwareStatus.value = try {
                when (r.checkFirmwareModification()) {
                    true -> FirmwareStatus.Modded
                    false -> FirmwareStatus.Stock
                    null -> FirmwareStatus.NoResponse
                }
            } catch (e: Exception) {
                FirmwareStatus.Error(e.message ?: "Unknown error")
            }
            if (_firmwareStatus.value == FirmwareStatus.Modded) _scanBlocked.value = null
            updateFrequencyPlan(r.localParams)
        }
    }

    /**
     * Writes edited radio parameters (ATI5 names -> values) in one AT session
     * with a single save + reboot. A running scan is stopped first since the
     * radio stops listening while it's in AT mode and rebooting.
     */
    fun applyRadioParams(changes: Map<String, String>) {
        val r = radio ?: run {
            LogBus.e("Not connected to a radio.")
            return
        }
        if (changes.isEmpty()) return
        runBusy {
            val running = scanJob
            if (running != null) {
                stopScan("radio settings changed")
                running.join()
            }
            val started = System.currentTimeMillis()
            val accepted = r.writeParams(changes)
            val ms = System.currentTimeMillis() - started
            val rejected = changes.keys - accepted.keys
            if (accepted.isNotEmpty()) {
                LogBus.event("RADIO", "Saved ${accepted.entries.joinToString { "${it.key}=${it.value}" }} and rebooted ($ms ms)")
            }
            if (rejected.isNotEmpty()) {
                LogBus.event("RADIO", "Not saved: ${rejected.joinToString()}", LogLevel.WARNING)
            }
            if (accepted.keys.any { it in HOP_TABLE_KEYS }) {
                _frequencyPlan.value = null
                updateFrequencyPlan(r.localParams)
            }
            accepted["NETID"]?.toIntOrNull()?.let { netId ->
                // Manually retuned: no longer locked on anything, but this NetID is what it hears without promiscuous mode.
                targetNetId = netId
                _lockState.value = LockState.None
            }
            DetectionLog.record("radio_config", mapOf("written" to accepted, "rejected" to rejected.toList(), "duration_ms" to ms))
        }
    }

    fun setPromiscuousMode(enabled: Boolean) = applyRadioParams(mapOf("PROMISCUOUS_MODE" to if (enabled) "1" else "0"))

    fun rebootRadio() {
        val r = radio ?: return
        runBusy {
            val running = scanJob
            if (running != null) {
                stopScan("radio rebooted")
                running.join()
            }
            r.resetDevice()
        }
    }

    // ------------------------------------------------------------- alerts & TAK

    fun updateAlertSettings(transform: (AlertSettings) -> AlertSettings) {
        val next = transform(_alertSettings.value)
        if (next == _alertSettings.value) return
        if (!next.tak.enabled && _alertSettings.value.tak.enabled) tak.close()
        _alertSettings.value = next
        alertStore.save(next)
    }

    fun canPostNotifications(): Boolean = alerts.canPostNotifications()

    fun sendTestAlert() {
        val pos = DetectionLog.observerPosition()
        alerts.fire(
            Alert(AlertTrigger.TEST, netId = 0, message = "Alerts are working.", lat = pos?.first, lon = pos?.second, fields = mapOf("test" to true)),
            _alertSettings.value
        )
    }

    /** Drops a marker at the phone's position so TAK reception can be checked; false if location is unknown. */
    fun sendTakTest(): Boolean {
        val pos = DetectionLog.observerPosition() ?: return false
        tak.sendTest(pos.first, pos.second, _alertSettings.value.tak)
        return true
    }

    private fun raise(trigger: AlertTrigger, netId: Int, message: String, intel: DroneIntel? = null) {
        val settings = _alertSettings.value
        val t = settings.triggers
        val wanted = when (trigger) {
            AlertTrigger.NEW_DRONE -> t.newDrone
            AlertTrigger.ARMED -> t.armed
            AlertTrigger.FIRST_GPS -> t.firstGps
            AlertTrigger.CRITICAL -> t.critical
            AlertTrigger.PROXIMITY -> t.proximity
            AlertTrigger.STRONG_SIGNAL -> t.strongSignal
            AlertTrigger.TEST -> true
        }
        if (!wanted) return
        if (trigger != AlertTrigger.TEST && isFriendly(netId)) return
        val i = intel ?: _intel.value[netId]
        val drone = currentDrones()[netId]
        val freq = drone?.lastChannel?.let { ch -> frequencyPlan.value?.takeIf { ch < it.channels }?.let { formatMHz(it.channelHz(ch)) } }
        alerts.fire(
            Alert(
                trigger, netId, message, lat = i?.position?.lat, lon = i?.position?.lon,
                fields = mapOf(
                    "freq_mhz" to freq, "packets" to drone?.packetsCount,
                    "vehicle_type" to i?.vehicleType, "autopilot" to i?.autopilot, "firmware" to i?.firmware,
                    "armed" to i?.armed, "mode" to i?.flightMode, "alt_rel_m" to i?.altRelM,
                    "ground_speed_ms" to i?.groundSpeed, "heading_deg" to i?.headingDeg,
                    "battery_pct" to i?.battery?.remainingPct, "battery_v" to i?.battery?.voltageV,
                    "home_lat" to i?.home?.lat, "home_lon" to i?.home?.lon,
                    "gcs_lat" to i?.gcsPosition?.lat, "gcs_lon" to i?.gcsPosition?.lon,
                    "rssi_dbm" to drone?.rssiDbm
                ),
                label = _knownDrones.value[netId]?.name?.takeIf { it.isNotBlank() }
            ),
            settings
        )
    }

    override fun onCleared() {
        // Only when the app is really closing (not on rotation): drop the link and the service.
        lockWatchdog?.cancel()
        scanJob?.cancel()
        transport?.let { t ->
            t.onConnectionLost = null
            // viewModelScope is already cancelled here; close on a short-lived scope instead.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { t.close() }
        }
        transport = null
        ScanService.stop(getApplication())
        ScanService.onStopRequested = null
        tak.close()
        super.onCleared()
    }

    fun startPassiveScan(stopOnDetect: Boolean, scanTimeoutMs: Long) {
        autoLockArmed = true
        loggedNetIds.clear()
        locatedNetIds.clear()
        alerts.resetCooldowns()
        LogBus.event("SCAN", "Passive scan started${frequencyPlan.value?.let { " (" + formatMHz(it.lowHz, 2) + "–" + formatMHz(it.highHz, 2) + " MHz)" } ?: ""}")
        DetectionLog.record("scan_start", mapOf("mode" to "passive", "ecc_sweep" to _eccSweep.value))
        launchPassiveScan(stopOnDetect, scanTimeoutMs, initial = emptyMap(), fresh = true)
    }

    /**
     * Runs the passive listener. Every MAVLink message heard, for any NetID,
     * feeds that drone's intel, the map and the logs; it also drives auto-lock. [initial] keeps the current list when resuming after a lock.
     */
    private fun launchPassiveScan(
        stopOnDetect: Boolean,
        scanTimeoutMs: Long,
        initial: Map<Int, DiscoveredDrone>,
        /** True for a user-started scan; false when resuming after a lock (keep the radio's config). */
        fresh: Boolean = false
    ) {
        val engine = scanEngine ?: return
        val previous = scanJob
        scanJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _scanState.value = ScanState.Running(ScanMode.PASSIVE, initial)
            // A fresh scan without the sweep listens on the normal (non-ECC) framing.
            val wantEcc = if (fresh && !_eccSweep.value) false else null
            if (!ensureModdedFirmware(ScanMode.PASSIVE, initial, wantEcc)) return@launch
            _listening.value = true
            try {
                ensureFrequencyPlan()
                val deadline = if (scanTimeoutMs > 0) System.currentTimeMillis() + scanTimeoutMs else Long.MAX_VALUE
                var drones = initial
                while (true) {
                    val r = radio ?: break
                    val eccNow = r.localParams?.get("ECC")?.trim() == "1"
                    // ECC (golay) networks are only audible with ECC on and vice versa,
                    // so the sweep alternates; it pauses while locked onto a target.
                    val sweeping = _eccSweep.value && _lockState.value !is LockState.Locked
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val segment = if (sweeping) minOf(remaining, ECC_DWELL_MS) else if (deadline == Long.MAX_VALUE) 0 else remaining
                    val before = drones.values.sumOf { it.packetsCount }
                    engine.passiveScan(stopOnDetect, segment, drones, eccNow, ::onMavlink).collect { d ->
                        drones = d
                        _scanState.value = ScanState.Running(ScanMode.PASSIVE, d)
                        logNewDetections(d)
                        checkSignalAlerts(d)
                        maybeAutoLock(d)
                    }
                    val heard = drones.values.sumOf { it.packetsCount } > before
                    if (!sweeping || (stopOnDetect && heard) || System.currentTimeMillis() >= deadline) break
                    if (_lockState.value is LockState.Locked || _lockState.value is LockState.Locking) continue
                    snapshotBaseline(r)
                    val next = if (eccNow) "0" else "1"
                    LogBus.d("ECC sweep: switching ECC to $next")
                    _radioPrep.value = if (next == "1") "Listening for ECC links…" else null
                    r.writeParams(mapOf("ECC" to next))
                }
                _radioPrep.value = null
                _scanState.value = ScanState.Stopped(ScanMode.PASSIVE, currentDrones(), "finished")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.e("Passive scan error: ${e.message}")
            } finally {
                _listening.value = false
                _radioPrep.value = null
            }
        }
    }

    fun startDeepScan(stopOnDetect: Boolean, scanTimeoutMs: Long) {
        val engine = scanEngine ?: return
        val previous = scanJob
        scanJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            _scanState.value = ScanState.Running(ScanMode.DEEP, emptyMap())
            if (!ensureModdedFirmware(ScanMode.DEEP, emptyMap(), wantEcc = null)) return@launch
            loggedNetIds.clear()
            alerts.resetCooldowns()
            LogBus.event("SCAN", "Deep scan started")
            DetectionLog.record("scan_start", mapOf("mode" to "deep"))
            try {
                ensureFrequencyPlan()
                engine.deepScan(stopOnDetect, scanTimeoutMs).collect { drones ->
                    _scanState.value = ScanState.Running(ScanMode.DEEP, drones)
                    logNewDetections(drones)
                }
                _scanState.value = ScanState.Stopped(ScanMode.DEEP, currentDrones(), "finished")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.e("Deep scan error: ${e.message}")
            }
        }
    }

    /**
     * Scanning depends on the SiKW00F firmware: stock SiK hardware-filters
     * other networks' packets and has no promiscuous mode (S16), so a scan
     * would silently find nothing. Verifies with ATI5 unless the radio is
     * already known to be modded (the same read also yields the hop table),
     * and refuses the scan for stock or unverifiable radios.
     */
    private suspend fun ensureModdedFirmware(mode: ScanMode, drones: Map<Int, DiscoveredDrone>, wantEcc: Boolean?): Boolean {
        val r = radio ?: return false
        if (_firmwareStatus.value != FirmwareStatus.Modded && _firmwareStatus.value != FirmwareStatus.Stock) {
            _firmwareStatus.value = FirmwareStatus.Checking
            _firmwareStatus.value = try {
                when (r.checkFirmwareModification()) {
                    true -> FirmwareStatus.Modded
                    false -> FirmwareStatus.Stock
                    null -> FirmwareStatus.NoResponse
                }
            } catch (e: CancellationException) {
                _firmwareStatus.value = FirmwareStatus.Unknown
                throw e
            } catch (e: Exception) {
                FirmwareStatus.Error(e.message ?: "Unknown error")
            }
            updateFrequencyPlan(r.localParams)
        }
        val reason = when (val status = _firmwareStatus.value) {
            FirmwareStatus.Modded -> if (ensureScanConfig(r, wantEcc)) {
                _scanBlocked.value = null
                return true
            } else {
                "couldn't switch promiscuous mode on (S16)"
            }
            FirmwareStatus.Stock -> "radio is running stock SiK firmware"
            FirmwareStatus.NoResponse -> "radio didn't answer the firmware check"
            is FirmwareStatus.Error -> "firmware check failed (${status.message})"
            else -> "firmware not verified"
        }
        _scanBlocked.value = reason
        val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
        _scanState.value = ScanState.Stopped(mode, drones, "blocked")
        LogBus.event("SCAN", "$label scan blocked: $reason. Scanning needs the SiKW00F modded firmware with promiscuous mode on.", LogLevel.WARNING)
        return false
    }

    /**
     * The firmware check only proves the radio *supports* promiscuous mode;
     * this makes sure it's actually on (S16=1, saved, rebooted into) before
     * listening, using the value ATI5 reported.
     */
    private suspend fun ensureScanConfig(r: SikRadio, wantEcc: Boolean?): Boolean {
        val changes = linkedMapOf<String, String>()
        if (r.promiscuousEnabled != true) changes["PROMISCUOUS_MODE"] = "1"
        val eccNow = r.localParams?.get("ECC")?.trim()
        if (wantEcc != null && eccNow != null && (eccNow == "1") != wantEcc) changes["ECC"] = if (wantEcc) "1" else "0"
        if (changes.isEmpty()) return true
        snapshotBaseline(r)
        val current = r.localParams?.get("PROMISCUOUS_MODE") ?: "unknown"
        if ("PROMISCUOUS_MODE" in changes) LogBus.event("SCAN", "Promiscuous mode is off (S16=$current); switching it on, saving and rebooting the radio")
        if ("ECC" in changes) LogBus.event("SCAN", "Setting ECC=${changes["ECC"]} to hear ${if (wantEcc == true) "ECC" else "standard"} links")
        _radioPrep.value = "Preparing radio…"
        val started = System.currentTimeMillis()
        var written: Map<String, String> = emptyMap()
        try {
            written = r.writeParams(changes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBus.e("Preparing the radio failed: ${e.message}")
        } finally {
            _radioPrep.value = null
        }
        // Only promiscuous mode is essential; a failed ECC switch just means fewer links are audible.
        val ok = "PROMISCUOUS_MODE" !in changes || "PROMISCUOUS_MODE" in written
        val ms = System.currentTimeMillis() - started
        if (written.isNotEmpty()) {
            LogBus.event("SCAN", "Radio ready (${written.entries.joinToString { "${it.key}=${it.value}" }} saved, rebooted) in $ms ms")
        }
        (changes.keys - written.keys).takeIf { it.isNotEmpty() }?.let {
            LogBus.event("SCAN", "Radio didn't accept ${it.joinToString()}", LogLevel.WARNING)
        }
        DetectionLog.record("radio_config", mapOf("written" to written, "requested" to changes, "ok" to ok, "duration_ms" to ms))
        return ok
    }

    fun stopScan(reason: String = "stopped by user") {
        if (scanJob == null) return
        scanJob?.cancel()
        scanJob = null
        val running = _scanState.value as? ScanState.Running ?: return
        _scanState.value = ScanState.Stopped(running.mode, running.drones, reason)
        LogBus.event("SCAN", "Scan stopped ($reason), ${running.drones.size} NetID(s) seen")
        writeSummaries(reason)
    }

    private fun currentDrones(): Map<Int, DiscoveredDrone> = when (val s = _scanState.value) {
        is ScanState.Running -> s.drones
        is ScanState.Stopped -> s.drones
        ScanState.Idle -> emptyMap()
    }

    fun setAutoLock(enabled: Boolean) {
        _autoLock.value = enabled
    }

    /** Locks onto the first drone heard a few times, once per user-started scan. */
    private fun maybeAutoLock(drones: Map<Int, DiscoveredDrone>) {
        if (!_autoLock.value || !autoLockArmed) return
        if (_lockState.value is LockState.Locking || _lockState.value is LockState.Locked) return
        val candidate = drones.values.firstOrNull { it.packetsCount >= AUTO_LOCK_MIN_PACKETS && !isFriendly(it.netId) } ?: return
        autoLockArmed = false
        LogBus.event("LOCK", "Auto-lock on NetID ${candidate.netId} (${candidate.packetsCount} packets heard)")
        lockOn(candidate.netId)
    }

    /**
     * Quick lock (see LockEngine): pauses listening, retunes this radio's hop
     * sequence to [netId] in one AT session, then resumes listening so the
     * target's packets, telemetry and GPS stream in continuously.
     */
    fun lockOn(netId: Int) {
        val engine = lockEngine ?: run {
            LogBus.e("Not connected to a radio.")
            return
        }
        if (_lockState.value is LockState.Locking) return
        viewModelScope.launch {
            _lockState.value = LockState.Locking(netId)
            _busy.value = true
            val drones = currentDrones()
            scanJob?.cancelAndJoin()
            scanJob = null
            lockWatchdog?.cancel()
            radio?.let { snapshotBaseline(it) }
            val started = System.currentTimeMillis()
            var locked = false
            try {
                val written = engine.quickLock(netId, drones[netId]?.params.orEmpty())
                locked = true
                onLocked(netId, written)
                val ms = System.currentTimeMillis() - started
                LogBus.event("LOCK", "Locked on NetID $netId in $ms ms (${written.keys.joinToString()})")
                DetectionLog.record("lock", mapOf("netId" to netId, "method" to "quick", "duration_ms" to ms, "written" to written.toString()))
            } catch (e: CancellationException) {
                _lockState.value = LockState.None
                throw e
            } catch (e: Exception) {
                LogBus.event("LOCK", "Quick lock on NetID $netId failed: ${e.message}", LogLevel.ERROR)
                DetectionLog.record("lock", mapOf("netId" to netId, "method" to "quick", "error" to e.message))
                _lockState.value = LockState.Failed(netId, e.message ?: "Lock failed")
            } finally {
                _busy.value = false
            }
            if (radio != null) launchPassiveScan(stopOnDetect = false, scanTimeoutMs = 0, initial = drones)
            if (locked) watchLock(netId, started, fullLockTried = false)
        }
    }

    /**
     * Checks that a lock actually brought the target in. MAVLink since the
     * lock = good. Packets but no MAVLink = the radio is in step but the
     * payload doesn't decode (encrypted / not MAVLink), so retuning won't help.
     * Silence = try a full lock once, and if that's silent too, release the
     * lock so the radio isn't left tuned to nothing.
     */
    private fun watchLock(netId: Int, lockedAt: Long, fullLockTried: Boolean) {
        lockWatchdog?.cancel()
        val packetsAtLock = currentDrones()[netId]?.packetsCount ?: 0
        val payloadAtLock = currentDrones()[netId]?.payloadBytes ?: 0L
        lockWatchdog = viewModelScope.launch {
            kotlinx.coroutines.delay(LOCK_VERIFY_MS)
            if ((_lockState.value as? LockState.Locked)?.netId != netId) return@launch
            val mavlink = (_intel.value[netId]?.lastMessage ?: 0L) > lockedAt
            val drone = currentDrones()[netId]
            val packets = (drone?.packetsCount ?: 0) - packetsAtLock
            val payload = (drone?.payloadBytes ?: 0L) - payloadAtLock
            when {
                mavlink -> LogBus.event("LOCK", "Lock on NetID $netId verified: MAVLink flowing")
                packets > 0 && payload < packets * 2L -> LogBus.event(
                    "LOCK",
                    "Locked on NetID $netId: $packets packets, all empty. The radios are linked but nothing is " +
                        "sending telemetry (flight controller off or not outputting, or GCS disconnected)",
                    LogLevel.WARNING
                )
                packets > 0 -> LogBus.event(
                    "LOCK",
                    "Locked on NetID $netId: $packets packets with $payload bytes of data, but no MAVLink decoded " +
                        "(encrypted, or not a MAVLink link)",
                    LogLevel.WARNING
                )
                !fullLockTried -> {
                    LogBus.event("LOCK", "Nothing heard from NetID $netId ${LOCK_VERIFY_MS / 1000} s after the quick lock; trying a full lock", LogLevel.WARNING)
                    DetectionLog.record("lock_fallback", mapOf("netId" to netId, "step" to "full_lock"))
                    autotuneToClone(netId.toString(), verify = true)
                }
                else -> {
                    LogBus.event("LOCK", "Still nothing from NetID $netId after a full lock; releasing it", LogLevel.WARNING)
                    DetectionLog.record("lock_fallback", mapOf("netId" to netId, "step" to "release"))
                    releaseLock(failedMessage = "No signal after quick and full lock")
                }
            }
        }
    }

    /**
     * Puts the hop settings back the way they were before the first lock and
     * keeps listening. Promiscuous mode stays on so scanning carries on; the
     * Connect tab's Restore puts that back too.
     */
    fun releaseLock(failedMessage: String? = null) {
        val r = radio ?: return
        val base = _baseline.value
        val lockedId = (_lockState.value as? LockState.Locked)?.netId ?: (_lockState.value as? LockState.Failed)?.netId
        lockWatchdog?.cancel()
        runBusy {
            val wasListening = scanJob != null
            val drones = currentDrones()
            scanJob?.cancelAndJoin()
            scanJob = null
            val changes = base.orEmpty().filterKeys { it != "PROMISCUOUS_MODE" }
                .filter { (k, v) -> r.localParams?.get(k)?.trim() != v }
            val written = if (changes.isNotEmpty()) r.writeParams(changes) else emptyMap()
            targetNetId = null
            _lockState.value = if (failedMessage != null && lockedId != null) LockState.Failed(lockedId, failedMessage) else LockState.None
            if (written.keys.any { it in HOP_TABLE_KEYS }) {
                _frequencyPlan.value = null
                updateFrequencyPlan(r.localParams)
            }
            LogBus.event("LOCK", "Lock released" + (if (written.isEmpty()) "" else "; restored ${written.entries.joinToString { "${it.key}=${it.value}" }}"))
            DetectionLog.record("lock_release", mapOf("netId" to lockedId, "restored" to written, "reason" to (failedMessage ?: "user")))
            clearBaselineIfRestored(r)
            if (wasListening) launchPassiveScan(stopOnDetect = false, scanTimeoutMs = 0, initial = drones)
        }
    }

    /** Restores every saved setting, promiscuous mode included: the radio is back to how it was. */
    fun restoreRadio() {
        val r = radio ?: return
        val base = _baseline.value ?: return
        lockWatchdog?.cancel()
        runBusy {
            val running = scanJob
            if (running != null) {
                stopScan("radio restored")
                running.join()
            }
            val changes = base.filter { (k, v) -> r.localParams?.get(k)?.trim() != v }
            val written = if (changes.isNotEmpty()) r.writeParams(changes) else emptyMap()
            targetNetId = null
            _lockState.value = LockState.None
            _frequencyPlan.value = null
            updateFrequencyPlan(r.localParams)
            LogBus.event("RADIO", "Radio restored to its saved settings" + (if (written.isEmpty()) "" else ": ${written.entries.joinToString { "${it.key}=${it.value}" }}"))
            DetectionLog.record("radio_restore", mapOf("restored" to written, "failed" to (changes.keys - written.keys).toList()))
            if (changes.keys.all { it in written }) saveBaseline(null) else clearBaselineIfRestored(r)
        }
    }

    /** Forgets the saved settings (e.g. they belong to a different radio). */
    fun discardBaseline() = saveBaseline(null)

    /** Settings that differ between the radio now and its saved baseline. */
    fun baselineDiff(): Map<String, Pair<String?, String>> {
        val base = _baseline.value ?: return emptyMap()
        val now = _radioParams.value ?: return emptyMap()
        return base.mapNotNull { (k, v) -> now[k]?.trim()?.takeIf { it != v }?.let { k to (it to v) } }.toMap()
    }

    private fun snapshotBaseline(r: SikRadio) {
        if (_baseline.value != null) return
        val params = r.localParams ?: return
        val snap = RESTORE_KEYS.mapNotNull { k -> params[k]?.trim()?.let { k to it } }.toMap()
        if (snap.isEmpty()) return
        saveBaseline(snap)
        LogBus.i("Saved the radio's current settings so they can be restored later.")
    }

    private fun clearBaselineIfRestored(r: SikRadio) {
        val base = _baseline.value ?: return
        val now = r.localParams ?: return
        if (base.all { (k, v) -> now[k]?.trim() == v }) saveBaseline(null)
    }

    private fun loadBaseline(): Map<String, String>? = prefs.getString("baseline", null)?.let { raw ->
        runCatching {
            val j = org.json.JSONObject(raw)
            j.keys().asSequence().associateWith { j.getString(it) }
        }.getOrNull()
    }

    private fun saveBaseline(map: Map<String, String>?) {
        _baseline.value = map
        prefs.edit().apply {
            if (map == null) remove("baseline") else putString("baseline", org.json.JSONObject(map).toString())
        }.apply()
    }

    fun setEccSweep(enabled: Boolean) {
        _eccSweep.value = enabled
        prefs.edit().putBoolean("ecc_sweep", enabled).apply()
    }

    private fun onLocked(netId: Int, written: Map<String, String>) {
        targetNetId = netId
        radio?.updateLocalParams(written)
        // New NETID (and maybe band) = new hop table; null makes the next scan re-read it.
        _frequencyPlan.value = null
        updateFrequencyPlan(radio?.localParams)
        _lockState.value = LockState.Locked(netId)
    }

    /** Folds every decoded MAVLink message into that NetID's intel and logs what matters. */
    private fun onMavlink(netId: Int?, sysId: Int, compId: Int, payload: Any) {
        // null = bytes seen before any NetID header. Only when the radio is NOT in
        // promiscuous mode is the stream purely the network it's tuned to; in
        // promiscuous mode such bytes are leftovers (e.g. the tail of another
        // drone's packet right after a lock) and crediting them to the lock
        // target produced phantom intel and false "lock verified" results.
        val id = netId ?: (if (radio?.promiscuousEnabled == true) null else targetNetId) ?: return
        val before = _intel.value[id] ?: DroneIntel(netId = id)
        // A malformed or unexpected message must never take down the scan.
        val (after, events) = try {
            before.update(sysId, compId, payload)
        } catch (e: Exception) {
            LogBus.d("Couldn't fold ${payload.javaClass.simpleName} into NetID $id intel: ${e.message}")
            return
        }
        _intel.value = _intel.value + (id to after)
        val fix = after.position
        if (fix != null && after.positionTime != before.positionTime) onVehiclePosition(id, after, fix.lat, fix.lon)
        events.forEach { logIntelEvent(id, it, after) }
    }

    private fun onVehiclePosition(id: Int, intel: DroneIntel, lat: Double, lon: Double) {
        val now = intel.positionTime ?: System.currentTimeMillis()
        _dronePositions.value = _dronePositions.value +
            (id to DronePosition(id, lat, lon, intel.altRelM, now, intel.positionSource ?: ""))
        val observer = DetectionLog.observerPosition()
        val distance = observer?.let { distanceM(it.first, it.second, lat, lon) }
        tak.publish(intel, currentFreqMHz(id), _alertSettings.value.tak, _knownDrones.value[id])
        if (locatedNetIds.add(id)) {
            raise(AlertTrigger.FIRST_GPS, id, "GPS position acquired" + (distance?.let { ", ${formatDistance(it)} from you" } ?: ""), intel)
        }
        val radius = _alertSettings.value.triggers.proximityM
        if (distance != null && distance <= radius) {
            raise(AlertTrigger.PROXIMITY, id, "${formatDistance(distance)} from you (inside ${radius} m)", intel)
        }
        // Structured track, throttled so long sessions stay a manageable size.
        if (now - (lastPositionRecord[id] ?: 0L) < POSITION_RECORD_INTERVAL_MS) return
        lastPositionRecord[id] = now
        DetectionLog.record(
            "position",
            mapOf(
                "netId" to id, "lat" to lat, "lon" to lon,
                "alt_rel_m" to intel.altRelM, "alt_msl_m" to intel.altMslM,
                "ground_speed_ms" to intel.groundSpeed, "heading_deg" to intel.headingDeg,
                "climb_ms" to intel.climbRate, "source" to intel.positionSource,
                "armed" to intel.armed, "mode" to intel.flightMode,
                "distance_from_observer_m" to observer?.let { distanceM(it.first, it.second, lat, lon).toInt() },
                "rssi_dbm" to currentDrones()[id]?.rssiDbm
            )
        )
    }

    private fun logIntelEvent(id: Int, event: IntelEvent, intel: DroneIntel) {
        val level = when (event.severity) {
            IntelEvent.Severity.CRITICAL -> LogLevel.ERROR
            IntelEvent.Severity.WARNING -> LogLevel.WARNING
            else -> LogLevel.INFO
        }
        LogBus.event(event.kind.name, "NetID $id: ${event.text}", level)
        if (event.kind == IntelEvent.Kind.ARM && intel.armed == true) raise(AlertTrigger.ARMED, id, event.text, intel)
        else if (event.severity == IntelEvent.Severity.CRITICAL) raise(AlertTrigger.CRITICAL, id, event.text, intel)
        DetectionLog.record(
            "intel",
            mapOf(
                "netId" to id, "kind" to event.kind.name, "severity" to event.severity.name, "text" to event.text,
                "vehicle_sysid" to intel.vehicleSysId, "armed" to intel.armed, "mode" to intel.flightMode,
                "lat" to intel.position?.lat, "lon" to intel.position?.lon, "alt_rel_m" to intel.altRelM
            )
        )
    }

    /** Logs each NetID once per scan session, with frequency and where the phone was. */
    private fun logNewDetections(drones: Map<Int, DiscoveredDrone>) {
        for (drone in drones.values) {
            if (drone.netId in loggedNetIds || drone.lastChannel == null) continue
            loggedNetIds += drone.netId
            val plan = frequencyPlan.value
            val ch = drone.lastChannel
            val freq = plan?.takeIf { ch < it.channels }?.let { formatMHz(it.channelHz(ch)) }
            val observer = DetectionLog.observerPosition()
            LogBus.event(
                "DETECT",
                "NetID ${drone.netId} detected" + (freq?.let { " on $it MHz" } ?: "") + " (ch $ch)" +
                    (observer?.let { " · observer %.5f, %.5f".format(java.util.Locale.US, it.first, it.second) } ?: ""),
                LogLevel.WARNING
            )
            DetectionLog.record(
                "detection",
                mapOf(
                    "netId" to drone.netId, "channel" to ch, "freq_mhz" to freq, "packets" to drone.packetsCount,
                    "rssi_dbm" to drone.rssiDbm, "ecc" to drone.ecc
                )
            )
            raise(AlertTrigger.NEW_DRONE, drone.netId, "Heard" + (freq?.let { " on $it MHz" } ?: " on channel $ch"))
        }
    }

    /** One structured summary per NetID (and a readable line) when a scan ends or the radio disconnects. */
    private fun writeSummaries(reason: String) {
        val drones = currentDrones()
        val ids = drones.keys + _intel.value.keys
        for (id in ids) {
            val d = drones[id]
            val i = _intel.value[id]
            val freqs = d?.channelHits?.keys?.sorted()?.mapNotNull { ch ->
                frequencyPlan.value?.takeIf { ch < it.channels }?.let { formatMHz(it.channelHz(ch)) }
            }
            DetectionLog.record(
                "summary",
                mapOf(
                    "netId" to id, "reason" to reason, "packets" to d?.packetsCount, "mavlink_messages" to d?.mavlinkCount,
                    "rssi_peak_dbm" to d?.rssiPeakDbm, "rssi_last_dbm" to d?.rssiDbm, "ecc" to d?.ecc,
                    "payload_bytes" to d?.payloadBytes, "link_idle" to d?.linkIdle,
                    "freqs_mhz" to freqs, "vehicle_type" to i?.vehicleType, "autopilot" to i?.autopilot,
                    "firmware" to i?.firmware, "frame" to i?.frame, "vehicle_sysid" to i?.vehicleSysId,
                    "armed" to i?.armed, "mode" to i?.flightMode, "battery_pct" to i?.battery?.remainingPct,
                    "battery_v" to i?.battery?.voltageV, "lat" to i?.position?.lat, "lon" to i?.position?.lon,
                    "home_lat" to i?.home?.lat, "home_lon" to i?.home?.lon, "gcs_sysids" to i?.gcsSysIds?.toList(),
                    "mission_items" to i?.mission?.size, "params_captured" to i?.params?.size, "simulated" to i?.simulated,
                    "events" to i?.events?.size, "battery_message_seen" to i?.batteryMessageSeen,
                    "message_counts" to i?.messageCounts
                )
            )
            if (i != null) {
                LogBus.event(
                    "SUMMARY",
                    "NetID $id: ${i.vehicleType ?: "unknown vehicle"}" +
                        (i.autopilot?.let { " / $it" } ?: "") + (i.firmware?.let { " / $it" } ?: "") +
                        ", ${d?.packetsCount ?: 0} packets, ${i.messageCounts.values.sum()} MAVLink msgs" +
                        (i.position?.let { ", last at %.5f, %.5f".format(java.util.Locale.US, it.lat, it.lon) } ?: "")
                )
            }
        }
    }

    /**
     * Strong-signal warning: uses the smoothed reading (a few packets) so one
     * spike can't trigger it, and only once a drone has been heard a little.
     * Repeats are rate limited by the alert cooldown.
     */
    private fun checkSignalAlerts(drones: Map<Int, DiscoveredDrone>) {
        val t = _alertSettings.value.triggers
        if (!t.strongSignal) return
        for (d in drones.values) {
            val smoothed = d.rssiFast ?: continue
            if (d.packetsCount < 5 || smoothed < t.strongSignalDbm) continue
            raise(AlertTrigger.STRONG_SIGNAL, d.netId, "Signal ${smoothed.toInt()} dBm (alert at ${t.strongSignalDbm} dBm): likely close")
        }
    }

    private fun currentFreqMHz(netId: Int): String? {
        val ch = currentDrones()[netId]?.lastChannel ?: return null
        return frequencyPlan.value?.takeIf { ch < it.channels }?.let { formatMHz(it.channelHz(ch)) }
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(java.util.Locale.US, m / 1000)

    fun updatePhoneLocation(lat: Double, lon: Double, accuracyM: Float) {
        _phoneLocation.value = lat to lon
        DetectionLog.setObserver(lat, lon, accuracyM)
    }

    suspend fun dumpParams(netId: String): List<String> =
        autotuneEngine?.dumpParams(netId) ?: emptyList()

    /**
     * Full lock: dumps the target's live params over RTI5, then clones them
     * onto this radio. Slower than [lockOn] but copies settings a deep scan
     * didn't capture. Resumes listening afterwards.
     */
    fun autotuneToClone(targetNetId: String, verify: Boolean = false) = runBusy {
        _autotuneResult.value = null
        val netId = targetNetId.trim().toIntOrNull()
        val engine = autotuneEngine ?: run {
            LogBus.e("Not connected to a radio.")
            return@runBusy
        }
        val drones = currentDrones()
        scanJob?.cancelAndJoin()
        scanJob = null
        if (!verify) lockWatchdog?.cancel()
        radio?.let { snapshotBaseline(it) }
        if (netId != null) _lockState.value = LockState.Locking(netId)
        val started = System.currentTimeMillis()
        try {
            val result = engine.autotuneToClone(targetNetId)
            _autotuneResult.value = result
            if (netId != null) {
                if (result.appliedKeys.isNotEmpty()) {
                    onLocked(netId, result.extractedParams + ("PROMISCUOUS_MODE" to "1"))
                } else {
                    _lockState.value = LockState.Failed(netId, "Couldn't read this drone's settings")
                }
            }
        } finally {
            if (_lockState.value is LockState.Locking) _lockState.value = LockState.None
            if (radio != null) launchPassiveScan(stopOnDetect = false, scanTimeoutMs = 0, initial = drones)
        }
        // Part of the quick-lock fallback: if the full lock didn't apply, or stays silent, release.
        if (verify && netId != null) {
            if ((_lockState.value as? LockState.Locked)?.netId == netId) watchLock(netId, started, fullLockTried = true)
            else releaseLock(failedMessage = "Full lock couldn't read this drone's settings")
        }
    }

    fun clearAutotuneResult() {
        _autotuneResult.value = null
    }

    /**
     * Flashes an `.ihx` firmware image bundled under assets/firmware/ (e.g.
     * "hm_trp_modded.ihx" for the SiKW00F promiscuous-mode build, or
     * "hm_trp_stock.ihx" to reset a radio back to stock ArduPilot/SiK). Only
     * the upload protocol runs on-device — the firmware itself is built on a
     * desktop with `make`/`sdcc`, same as the Python tool's `--flash`.
     */
    fun flashFirmware(context: Context, assetFileName: String, eraseParams: Boolean) {
        val t = transport
        if (t == null) {
            LogBus.e("Not connected to a radio.")
            return
        }
        val baud = (connectionState.value as? ConnectionState.Connected)?.baud ?: 57600

        viewModelScope.launch {
            _flashing.value = true
            _flashEvents.value = emptyList()
            _firmwareStatus.value = FirmwareStatus.Unknown // firmware is about to change; re-check after flashing
            _frequencyPlan.value = null
            try {
                val lines = context.assets.open("firmware/$assetFileName").bufferedReader().readLines()
                val firmware = IntelHexFirmware(lines)
                LogBus.i("[FLASH] Loaded $assetFileName: ${firmware.totalSize()} bytes across ${firmware.code().size} block(s).")

                val uploader = FirmwareUploader(t, atBaudRate = baud)
                uploader.upload(firmware, eraseParams) { event ->
                    _flashEvents.value = _flashEvents.value + event
                    when (event) {
                        is FlashEvent.Stage -> LogBus.i("[FLASH] ${event.message}")
                        is FlashEvent.Failed -> LogBus.e("[FLASH] ${event.message}")
                        FlashEvent.Done -> {
                            LogBus.i("[FLASH] Done — radio is rebooting into the new firmware. Unplug it and plug it back in.")
                            _replugNeeded.value = true
                            // New firmware may have new defaults; nothing cached from the old one applies.
                            _radioParams.value = null
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                LogBus.e("[FLASH] Flash failed: ${e.message}")
                _flashEvents.value = _flashEvents.value + FlashEvent.Failed(e.message ?: "Unknown error")
            } finally {
                _flashing.value = false
            }
        }
    }

    /**
     * Reads the local radio's ATI5 params once per connection so scan hits can
     * be shown in MHz. Best effort: a failure just leaves channels as numbers.
     */
    private suspend fun ensureFrequencyPlan() {
        if (_frequencyPlan.value != null) return
        val r = radio ?: return
        try {
            updateFrequencyPlan(r.readLocalParams())
        } catch (e: Exception) {
            LogBus.w("Couldn't read radio params for MHz mapping: ${e.message}")
        }
    }

    private fun updateFrequencyPlan(params: Map<String, String>?) {
        val plan = params?.let(FrequencyPlan::fromParams) ?: return
        _frequencyPlan.value = plan
        LogBus.i(
            "Hop table: ${plan.channels} ch, %.3f–%.3f MHz, step %d kHz"
                .format(plan.lowHz / 1e6, plan.highHz / 1e6, plan.stepHz / 1000)
        )
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                LogBus.e("Operation failed: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }
}
