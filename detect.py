import numpy as np
import matplotlib.pyplot as plt
from pyhackrf2 import HackRF
from scipy.fft import fft, fftshift
import time
from matplotlib.animation import FuncAnimation
from sklearn.decomposition import FastICA
from collections import defaultdict

class SiKRadioDetector:
    """
    A comprehensive detector for SiK radios, leveraging multiple detection techniques beyond FHSS to capture unique signal characteristics.
    """
    def __init__(self, start_freq=430e6, stop_freq=436e6, sample_rate=2e6, duration=0.1, power_threshold=-50, calibration_time=10):
        """
        Initialize the SiK Radio Detector.

        Args:
            start_freq (float): Start frequency in Hz.
            stop_freq (float): Stop frequency in Hz.
            sample_rate (float): Sample rate in Hz.
            duration (float): Duration of each scan step in seconds.
            power_threshold (float): Power threshold for detections in dB.
            calibration_time (int): Time in seconds for calibration phase.
        """
        self.start_freq = start_freq
        self.stop_freq = stop_freq
        self.sample_rate = sample_rate
        self.duration = duration
        self.power_threshold = power_threshold
        self.calibration_time = calibration_time
        self.hackrf = HackRF()
        self.hackrf.vga_gain = 20
        self.hackrf.lna_gain = 16
        self.power_matrix = np.zeros((50, int((self.stop_freq - self.start_freq) / (self.sample_rate / 2))))
        self.freq_range = np.arange(self.start_freq, self.stop_freq, self.sample_rate)
        self.calibrated_baseline = None

    def capture_samples(self):
        """
        Capture I/Q samples from the HackRF device.

        Returns:
            np.ndarray: Captured I/Q samples as a complex array.
        """
        num_samples = int(self.sample_rate * self.duration)
        self.hackrf.start_rx()
        time.sleep(self.duration)
        buffer = self.hackrf.buffer[:num_samples * 2]
        self.hackrf.stop_rx()

        iq_data = np.frombuffer(buffer, dtype=np.int8).astype(np.float32)
        return iq_data[::2] + 1j * iq_data[1::2]

    def compute_power_spectrum(self, iq_data):
        """
        Compute the power spectrum of I/Q data.

        Args:
            iq_data (np.ndarray): I/Q data as a complex array.

        Returns:
            np.ndarray: Power spectrum in dB.
        """
        spectrum = np.abs(fftshift(fft(iq_data)))**2
        return 10 * np.log10(spectrum + 1e-12)

    def scan_frequency_range(self):
        """
        Scan the frequency range and collect mean power levels using signal separation.

        Returns:
            tuple: Mean power levels and frequency range.
        """
        step_size = self.sample_rate / 2
        num_steps = int((self.stop_freq - self.start_freq) / step_size)
        freq_range = np.linspace(self.start_freq, self.stop_freq, num_steps, endpoint=False)
        power_levels = np.zeros(num_steps)

        for idx, center_freq in enumerate(freq_range):
            self.hackrf.sample_rate = self.sample_rate
            self.hackrf.center_freq = center_freq
            iq_data = self.capture_samples()
            separated_signals = self.separate_signals(iq_data)

            # Analyze each separated signal
            for signal in separated_signals:
                power_spectrum = self.compute_power_spectrum(signal)
                power_levels[idx] += power_spectrum.mean()  # Aggregate power levels for all sources

            power_levels[idx] /= len(separated_signals)  # Normalize by number of sources

        return power_levels, freq_range

    def separate_signals(self, iq_data):
        """
        Separate overlapping signals using Independent Component Analysis (ICA).

        Args:
            iq_data (np.ndarray): I/Q data as a complex array.

        Returns:
            list: Separated signal components.
        """
        real_iq = np.vstack((iq_data.real, iq_data.imag)).T
        ica = FastICA(n_components=2)
        separated_components = ica.fit_transform(real_iq)
        separated_signals = [separated_components[:, 0] + 1j * separated_components[:, 1]]
        return separated_signals

    def calibrate_environment(self):
        """
        Perform a calibration phase to establish the baseline RF environment.
        """
        print(f"Calibrating environment for {self.calibration_time} seconds...")
        start_time = time.time()
        baseline_readings = []

        while time.time() - start_time < self.calibration_time:
            power_levels, _ = self.scan_frequency_range()
            baseline_readings.append(power_levels)

        self.calibrated_baseline = np.mean(baseline_readings, axis=0)
        print("Calibration complete.")

    def detect_sik_signatures(self):
        """
        Run all detection heuristics and return confidence scores.

        Returns:
            tuple: Aggregated confidence score and individual clue scores.
        """
        confidence_scores = defaultdict(float)

        # Detection heuristics
        confidence_scores['Power Fluctuation'] = self.power_fluctuation_clue()
        confidence_scores['Hopping Pattern'] = self.hopping_pattern_clue()
        confidence_scores['Persistent Power'] = self.power_persistence_clue()
        confidence_scores['Timing Anomaly'] = self.timing_anomaly_clue()
        confidence_scores['Protocol Signature'] = self.protocol_signature_clue()

        weighted_score = sum(confidence_scores.values()) / len(confidence_scores)
        return weighted_score, confidence_scores

    def power_fluctuation_clue(self):
        """
        Detect rapid power changes across frequency bins.

        Returns:
            float: Confidence score [0, 1].
        """
        power_diff = np.abs(np.diff(self.power_matrix[-1, :]))
        significant_changes = np.sum(power_diff > 10)  # Tunable threshold
        return min(significant_changes / len(power_diff), 1.0)

    def hopping_pattern_clue(self):
        """
        Detect frequency hopping based on power peaks over time.

        Returns:
            float: Confidence score [0, 1].
        """
        peaks = [np.argmax(row) for row in self.power_matrix[-5:]]  # Last 5 scans
        unique_peaks = len(set(peaks))
        return unique_peaks / len(peaks)

    def power_persistence_clue(self):
        """
        Detect persistent power in specific bins above threshold.

        Returns:
            float: Confidence score [0, 1].
        """
        avg_power = np.mean(self.power_matrix[-5:, :], axis=0)
        persistent_bins = np.sum(avg_power > self.power_threshold)
        return min(persistent_bins / len(avg_power), 1.0)

    def timing_anomaly_clue(self):
        """
        Detect timing irregularities in transmissions that deviate from expected intervals.

        Returns:
            float: Confidence score [0, 1].
        """
        # Placeholder logic for timing anomaly detection
        return 0.0

    def protocol_signature_clue(self):
        """
        Identify known SiK protocol-specific features in the transmission.

        Returns:
            float: Confidence score [0, 1].
        """
        # Placeholder logic for protocol signature detection and future fingerprinting
        return 0.0

    def update_power_matrix(self):
        power_levels, _ = self.scan_frequency_range()
        self.power_matrix = np.roll(self.power_matrix, -1, axis=0)
        self.power_matrix[-1, :] = power_levels

    def animate_waterfall(self, i):
        self.update_power_matrix()
        confidence, scores = self.detect_sik_signatures()

        self.ax.clear()
        extent = [self.start_freq / 1e6, self.stop_freq / 1e6, 0, 10]
        self.ax.imshow(self.power_matrix, aspect='auto', extent=extent, cmap='jet', origin='lower')
        self.ax.set_xlabel("Frequency (MHz)")
        self.ax.set_ylabel("Scan Step")
        self.ax.set_title(f"SiK Radio Detection (Confidence: {confidence:.2f})")

        if confidence > 0.7:
            print(f"[!] SiK Radio Detected! Confidence: {confidence:.2f}")
            for clue, score in scores.items():
                print(f"  {clue}: {score:.2f}")

    def run(self):
        print("Starting SiK Radio Detector...")
        self.calibrate_environment()
        fig, self.ax = plt.subplots(figsize=(10, 6))
        ani = FuncAnimation(fig, self.animate_waterfall, interval=500, cache_frame_data=False)
        try:
            plt.show()
        except KeyboardInterrupt:
            print("\nStopping SiK Radio Detection...")
        finally:
            self.hackrf.close()

if __name__ == "__main__":
    detector = SiKRadioDetector(
        start_freq=430e6,
        stop_freq=436e6,
        sample_rate=2e6,
        duration=0.1,
        power_threshold=-50,
        calibration_time=10  # Time for calibration
    )
    detector.run()
