<h1 align="center">
    SiKW00F
    <a href="https://github.com/nicholasaleks/sikw00f"><img src="https://github.com/nicholasaleks/sikw00f/blob/master/SiKW00F.png?raw=true" alt="sikw00f"/></a>
</h1>
<p align="center">
  <b>The Drone SiK Radio Detection & MAVLink Telemetry Eavesdropping Toolkit.</b>
</p>

SiKW00F is a simple Python-based toolkit designed to detect and eavesdrop on drone telemetry that uses <a href="https://ardupilot.org/copter/docs/common-sik-telemetry-radio.html">SiK radio pairs</a> and the <a href="https://mavlink.io/en/">MAVLink</a> communication protocol. Leveraging a third attacker SiK radio running <a href="https://github.com/nicholasaleks/SiK">custom-modified firmware</a>, SiKW00F provides real-time, drone detection & promiscuous monitoring of drone communications between it and its Ground Control Station (GCS).

# How Does It Work?

SiK radios are widely used in drone telemetry due to their simplicity and robust communication capabilities. They typically employ <a href="https://en.wikipedia.org/wiki/Frequency-hopping_spread_spectrum#:~:text=Frequency%2Dhopping%20spread%20spectrum%20(FHSS,occupying%20a%20large%20spectral%20band.">Frequency Hopping Spread Spectrum (FHSS)</a>, which rapidly switches communication channels based on a pseudo-random sequence derived from a NetID. Under normal circumstances, a SiK radio enforces hardware filtering so it only accepts packets matching its configured NetID—other transmissions are discarded.

## Custom Attacker Firmware Modifications

Custom SiK Firmware Modification:
- NetID Header Control Bypasses
- Real-Time NetID Overwrite
- Silent Statistic Frames
- State Machine Synchronization

For more details on the firmware mod, go to: <a href="https://github.com/nicholasaleks/SiK">https://github.com/nicholasaleks/SiK</a>

# Operational Modes

## Passive Drone & NetID Detection (Promiscious Mode)

A third SiK radio, loaded with custom-modified firmware, is set to a promiscuous mode. It passively listens to all transmissions, capturing packets and extracting NetIDs from all nearby drones and ground control station telemetry communications. This mode identifies active SiK networks and gathers the necessary data (like NetIDs) to later target a specific telemetry link for eavesdropping.

[Picture of Passive Mode]

## Drone Telemetry Eavesdropping Mode

Using the data collected during promiscuous scanning, SiKW00F actively tunes the attacker SiK radio to follow the frequency hops dictated by the NetID and other parameters. This connection phase integrates active mode features by honing in on the FHSS sequence, thereby enabling detailed eavesdropping on the telemetry exchange between the drone and its GCS.

[Picture of Eavesdropping Mode]

### Example MAVLink Telemetry Eavesdropping

SiKW00F is designed to easily capture and display a wide range of MAVLink telemetry messages transmitted between a drone and its ground control station. Once the tool has locked onto the FHSS pattern using the NetID data, it can eavesdrop on key MAVLink messages, including:
- HEARTBEAT: Provides essential status information such as system type, autopilot type, and overall state.
- SYS_STATUS: Offers detailed system health metrics, including battery voltage, current consumption, and error status.
- GLOBAL_POSITION_INT: Displays the drone’s global positioning data such as latitude, longitude, altitude, and relative altitude.
- GPS_RAW_INT: Captures raw GPS data including timestamp, fix type, satellite count, and precise coordinates.
- ATTITUDE: Shows the drone’s orientation (roll, pitch, yaw) and the rates of change for these angles.
- VFR_HUD: Provides flight metrics like airspeed, ground speed, altitude, throttle percentage, and climb rate.
- HIGHRES_IMU: Delivers high-resolution inertial measurement data, including accelerometer, gyroscope, and magnetometer readings.
- RC_CHANNELS: Reflects information from the remote control channels, indicating pilot inputs.
- STATUSTEXT: Conveys system messages and warnings, offering insights into flight events and potential issues.
- PARAM_VALUE: Shows current parameter settings, which can help in understanding and tuning the drone’s performance.

## Installation

### Dependencies

Linux (Ubuntu/Debian)
```
sudo apt-get update
sudo apt-get install git make sdcc build-essential python3.11
```

MacOS (Ubuntu/Debian)
```
brew install git make sdcc python@3.11
xcode-select --install
```

## Installation
Clone the repository:

```
git clone https://github.com/nicholasaleks/sikw00f.git
cd sikw00f
```

## Usage

SiKW00F is driven by a simple Python CLI, **`sikw00f.py`**, which supports a range of arguments and commands to configure, flash, scan, or eavesdrop on SiK radios. Below is a quick reference for each category of arguments and usage examples.

---

### General Arguments

| Argument                  | Description                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------------|
| **`-h`**    | Displays help details on how to use sikw00f |
| **`-device <DEVICE>`**    | Specify the SiK radio device path (e.g. `/dev/ttyUSB0`). Overrides the config file if set.          |
| **`-baud <BAUD>`**        | Baud rate for the device (e.g. `57600`). Overrides the config file if set.                          |
| **`-config <FILE>`**      | Path to the configuration file (default: `conf/sikw00f.conf`).                                      |
| **`-log <LOGFILE>`**      | Log file path (overrides `logging.log_file` in config).                                             |
| **`-output_dir <DIR>`**   | Output directory (overrides `logging.output_dir` in config).                                        |
| **`-verbose`**            | Enable verbose (DEBUG) logging for detailed output.                                                 |

### Device Commands

| Argument                  | Description                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------------|
| **`--info`**    | Show info from the SiK radio (version, parameters, etc.). |
| **`--set-netid <NETID>`**        | Set NetID on the SiK device. |
| **`--set-minfreq <FREQ>`**      | Set minimum frequency (kHz). |
| **`--set-maxfreq <FREQ>	`**      | Set maximum frequency (kHz). |
| **`--set-channel-num <CHANNEL>`**   | Set the channel number (S10). |
| **`--disable-promiscuous-mode	`**  | Disable the custom firmware’s promiscuous mode. |
| **`--enable-promiscuous-mode	`**  | Enable the custom firmware’s promiscuous mode. |
| **`--reset	`**  | Reset the SiK device. (Virtual Power Cycle) |

### Flash Commands

| Argument                  | Description                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------------|
| **`--flash`**    | Flash the SiK device firmware (relies on -device/-baud or config file). |
| **`--flash check`**        | Check the SiK device firmware (relies on -device/-baud or config file). |


### Scanning Commands

| Argument                  | Description                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------------|
| **`--scan`**    | Passively scan for nearby drones (e.g., net IDs, channels). |
| **`--stop-on-detect`**        | Stop scanning on the first drone detection (no table updates after). |
| **`---autotune-on-detect`**        | Stop scanning & auto-tune once a drone is detected. |


### Autotuning Commands

| Argument                  | Description                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------------|
| **`--dump-params <NET_ID> <FILE>`**    | Dump the discovered drone’s parameters (e.g. S3=NETID, S8=MIN_FREQ, etc.) to an output file. |
| **`--set-params <NET_ID> <FILE>	`**        | Read parameters from a file and apply them to your local SiK device (placeholder logic). |
| **`--autotune <NET_ID>`**        | Perform a combined dump & set (auto-tune) to follow the discovered net ID’s FHSS pattern. |

### Eavesdropping Commands

| Argument                  | Description                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------------|
| **`--eavesdrop`**    | Eavesdrop on a drone’s MAVLink telemetry. Note: Be sure to --disable-promiscuous-mode before running eavsdrop and to ensure more packets can be collected if your SiK radio's parameters match the target drone’s parameters (i.e. net ID, min frequency, max frequency and number of channels) |


# Features
- Promiscuous Scanning Mode: Continuously monitors the radio spectrum to capture NetIDs and detect active SiK telemetry links.
- Drone Connection Eavesdropping: Actively locks onto the FHSS sequence using the captured NetID data, enabling real-time capture of MAVLink telemetry.
- Custom-Modified Firmware: Enhanced SiK radios offer dual-mode operation: passive capture for initial network identification and active tuning for detailed telemetry eavesdropping.
- Unified Configuration System: A centralized configuration file manages device settings, scanning timeouts, and other operational parameters to ensure seamless integration between scanning and connection phases.
- Data Logging: All captured telemetry data is stored in a SQLite database for historical analysis and further review.

# Features Coming Soon
- SiK Signal Strength Detection: Automatically display and set connection thresholds for signal strength
- Webhook Calls on Drone Detection: Automatically trigger external webhooks when a drone is detected.
- Android Mobile App: A mobile interface to monitor and control SiKW00F on the go.
- Dual SiK Radios: Enhance detection capabilities by splitting the frequency band across two radios.
- MavSploit Integration: Enable payload injection capabilities by integrating with the MavSploit framework.

# Contributions and Feedback

Contributions, feature requests, and bug reports are welcome! Open an issue or create a pull request on GitHub.

# License
This project is licensed under the MIT License.