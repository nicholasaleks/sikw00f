package com.sikw00f.android.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.sikw00f.android.LogBus
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ACTION_USB_PERMISSION = "com.sikw00f.android.USB_PERMISSION"

/** Lists every USB-serial-capable device currently attached (via OTG). */
fun listUsbSerialDrivers(context: Context): List<UsbSerialDriver> {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
}

/**
 * Direct USB-OTG transport to a SiK radio's USB-serial adapter.
 * Mirrors what `pyserial`'s `serial.Serial(device, baud)` gives the desktop tool.
 */
class UsbSerialTransport(
    private val context: Context,
    private val driver: UsbSerialDriver,
    private val baudRate: Int = 57600
) : SikTransport {

    override val label: String = "USB ${driver.device.deviceName} (${String.format("%04X:%04X", driver.device.vendorId, driver.device.productId)})"

    private var port: UsbSerialPort? = null
    override val isOpen: Boolean get() = port != null && !lost

    override var onConnectionLost: (() -> Unit)? = null
    @Volatile private var lost = false
    private var detachReceiverRegistered = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device?.deviceName == driver.device.deviceName) markLost("USB device detached")
        }
    }

    private fun markLost(reason: String?) {
        if (lost) return
        lost = true
        LogBus.w("[USB] Connection lost: ${reason ?: "I/O error"}")
        onConnectionLost?.invoke()
    }

    override suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!manager.hasPermission(driver.device)) {
                val granted = requestUsbPermission(context, manager, driver.device)
                if (!granted) {
                    return@withContext Result.failure(IllegalStateException("USB permission denied for ${driver.device.deviceName}"))
                }
            }

            val connection = manager.openDevice(driver.device)
                ?: return@withContext Result.failure(IllegalStateException("Could not open USB connection (no permission or device detached)"))

            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // SiK radios have no hardware flow control concerns for AT-mode traffic.
            port = p
            lost = false
            ContextCompat.registerReceiver(
                context, detachReceiver,
                IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            detachReceiverRegistered = true
            LogBus.i("[USB] Opened ${driver.device.deviceName} at $baudRate baud.")
            Result.success(Unit)
        } catch (e: Exception) {
            LogBus.e("[USB] Failed to open device: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        if (detachReceiverRegistered) {
            runCatching { context.unregisterReceiver(detachReceiver) }
            detachReceiverRegistered = false
        }
        try {
            port?.close()
        } catch (_: Exception) {
        } finally {
            port = null
        }
        Unit
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val p = port ?: throw IllegalStateException("Port not open")
        try {
            p.write(data, 2000)
        } catch (e: IOException) {
            markLost(e.message)
            throw e
        }
    }

    override suspend fun setBaudRate(baud: Int): Boolean = withContext(Dispatchers.IO) {
        val p = port ?: return@withContext false
        try {
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            LogBus.i("[USB] Switched baud rate to $baud.")
            true
        } catch (e: Exception) {
            LogBus.e("[USB] Failed to switch baud rate to $baud: ${e.message}")
            false
        }
    }

    override suspend fun readAvailable(): ByteArray = withContext(Dispatchers.IO) {
        val p = port ?: return@withContext ByteArray(0)
        val buffer = ByteArray(4096)
        try {
            // Short timeout: acts like pyserial's in_waiting-driven polling loop
            // rather than a long blocking read.
            val n = p.read(buffer, 100)
            if (n > 0) buffer.copyOf(n) else ByteArray(0)
        } catch (e: IOException) {
            // usb-serial-for-android reports a vanished device as an IOException.
            markLost(e.message)
            ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}

private suspend fun requestUsbPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean =
    suspendCoroutine { cont ->
        // Android 14+ rejects mutable PendingIntents wrapping implicit intents,
        // so pin it to our package. MUTABLE is required: UsbManager adds the
        // EXTRA_PERMISSION_GRANTED result to it.
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    cont.resume(granted)
                }
            }
        }
        // The PendingIntent is sent with our own identity, so a non-exported receiver still gets it.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        manager.requestPermission(device, permissionIntent)
    }
