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

# How to Use SiKW00F

## Installation
Clone the repository:

```
git clone https://github.com/nicholasaleks/sikw00f.git
cd sikw00f
```

## Install Dependencies

Before running **SiKW00F** and flashing the firmware, ensure your system has the following dependencies installed:

- **Git**  
  Used to clone the firmware repository.  
  **Installation:**  
  - **Linux:**  
    ```bash
    sudo apt-get install git
    ```  
  - **macOS:**  
    ```bash
    brew install git
    ```  
  - **Windows:**  
    Download and install [Git for Windows](https://gitforwindows.org/).

- **GNU Make**  
  Required to build the firmware using the provided Makefile.  
  **Installation:**  
  - **Linux:**  
    ```bash
    sudo apt-get install make
    ```  
  - **macOS:**  
    ```bash
    brew install make
    ```  
  - **Windows:**  
    Install via [MinGW](http://www.mingw.org/) or [Cygwin](https://www.cygwin.com/).

- **SDCC (Small Device C Compiler)**  
  Used to compile the firmware for the radio's microcontroller.  
  **Installation:**  
  - **Linux:**  
    ```bash
    sudo apt-get install sdcc
    ```  
  - **macOS:**  
    ```bash
    brew install sdcc
    ```  
  - **Windows:**  
    Download and install from the [SDCC website](http://sdcc.sourceforge.net/).

- **C Compiler and Build Tools**  
  Required for compiling and linking firmware.  
  **Installation:**  
  - **Linux:**  
    ```bash
    sudo apt-get install build-essential
    ```  
  - **macOS:**  
    Install the Xcode Command Line Tools:  
    ```bash
    xcode-select --install
    ```

- **Python 3.11**  
  Required to run the uploader script (`uploader.py`).  
  **Installation:**  
  - Download from the [official Python website](https://www.python.org/downloads/) or use your system’s package manager if available.

- **Unix-like Shell**  
  Required for running build scripts.  
  - **Linux/macOS:** Usually provided by default.  
  - **Windows:**  
    Consider installing [Git Bash](https://gitforwindows.org/) or [Cygwin](https://www.cygwin.com/) for a Unix-like shell environment.


## Basic Usage

Run the tool with default settings:

```
pipenv shell
python sikw00f.py
```

# Example Promiscious Mode
When a SiK radio is detected:

```

```

# Example Eavesdropping Mode
When you are eavesdropping on a drone MAVLink connection:

```

```

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