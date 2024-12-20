<h1 align="center">
    SiKW00F
    <a href="https://github.com/nicholasaleks/sikw00f"><img src="https://github.com/nicholasaleks/sikw00f/blob/main/SiKW00F.png?raw=true" alt="sikw00f"/></a>
</h1>
<p align="center">
  <b>The SiK Radio Detection & Fingerprinting Toolkit.</b>
</p>

SiKW00F is a Python-based toolkit designed to provide real-time detection and analysis of <a href="https://ardupilot.org/copter/docs/common-sik-telemetry-radio.html">SiK telemetry radios</a> which are commonly used by drones. By leveraging advanced signal processing and modular heuristics, it provides a robust detection and fingerprinting (coming soon) of radio signals using <a href="https://en.wikipedia.org/wiki/Frequency-hopping_spread_spectrum">Frequency Hopping Spread Spectrum (FHSS)</a> and SiK protocol-specific characteristics.

# How Does It Work?
SiKW00F is designed to use a <a href="https://greatscottgadgets.com/hackrf/one/">HackRF One</a> Software Defined Radio (SDR) to perform signal detection and analysis through a multi-step process:

## Calibration Phase:

SiKW00F initializes by calibrating to establish a baseline of the RF environment. This phase captures the ambient noise and static signals, reducing false positives.

## Signal Scanning and Separation:

The tool scans a specified frequency range (default: 430Mhz - 436MHz), capturing raw I/Q data using a HackRF One SDR.
Independent Component Analysis (ICA) is employed to separate overlapping signals and isolate individual sources.

## Detection Heuristics:

SiKW00F applies a range of modular detection heuristics:
- Power Fluctuation: Identifies rapid changes in signal power across frequency bins.
- Hopping Pattern: Detects frequency-hopping sequences typical of FHSS.
- Persistent Power: Analyzes specific frequency bins for sustained signal strength.
- Timing Anomalies: Detects irregularities in signal timing.
- Protocol Signatures: Looks for SiK-specific protocol features. (coming soon)

## Real-Time Feedback & Visualizations:

Confidence scores from all heuristics are combined into an overall detection confidence. Real-time alerts are provided when SiK radios are detected. Below is also an example screenshot of the simple visual waterfall detecting SiK radio activity.

<img src="https://github.com/nicholasaleks/sikw00f/blob/main/screenshot.png?raw=true" alt="sikw00f-rf-waterfall"/></a>

## (Coming Soon) Protocol & Device Fingerprinting 

Currently no fingerprinting (yet)

# How to Use SiKW00F

## Installation
Clone the repository:

```
git clone https://github.com/nicholasaleks/sikw00f.git
cd sikw00f
```

## Install dependencies:

```
pipenv install
```

## Basic Usage

Run the tool with default settings:

```
pipenv shell
python detect.py
```

The tool will:

- Calibrate the RF environment.
- Begin scanning and detecting SiK radios in the specified frequency range.
- Display real-time visualizations and log detections.

# Example Output
When a SiK radio is detected:

```
[!] SiK Radio Detected! Confidence: 0.85
  Power Fluctuation: 0.80
  Hopping Pattern: 0.90
  Persistent Power: 0.75
  Timing Anomaly: 0.50
  Protocol Signature: 0.60
```

# Contributions and Feedback

Contributions, feature requests, and bug reports are welcome! Open an issue or create a pull request on GitHub.

# License
This project is licensed under the MIT License.