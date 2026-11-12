package com.nautile.app.serial

import com.nautile.i18n.tr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.nautile.ssh.TerminalChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A USB-OTG serial adapter (FTDI, CP210x, CH34x, PL2303, CDC-ACM…) as a terminal channel. */
class SerialChannel private constructor(private val port: UsbSerialPort) : TerminalChannel {
    @Volatile private var closed = false

    override val input: InputStream = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val buf = ByteArray(maxOf(len, port.readEndpoint.maxPacketSize))
            while (!closed) {
                val n = try { port.read(buf, 200) } catch (e: IOException) { if (closed) return -1 else throw e }
                if (n > 0) {
                    val c = minOf(n, len)
                    System.arraycopy(buf, 0, b, off, c)
                    return c
                }
            }
            return -1
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = port.write(b.copyOfRange(off, off + len), 2_000)
    }

    override fun resize(columns: Int, rows: Int) {}

    override fun close() {
        closed = true
        runCatching { port.close() }
    }

    companion object {
        private const val ACTION_PERMISSION = "com.nautile.app.USB_PERMISSION"

        /** Blocking: opens the first serial adapter, asking for USB permission if needed. */
        fun open(context: Context, baud: Int, onStep: (String) -> Unit): SerialChannel {
            val usb = context.getSystemService(UsbManager::class.java)
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
                ?: throw IOException(tr("Aucun adaptateur série USB détecté (câble OTG branché ?)"))
            val device = driver.device
            onStep("Adaptateur : ${device.productName ?: device.deviceName}")
            if (!usb.hasPermission(device)) {
                onStep(tr("Autorisation USB demandée…"))
                if (!requestPermission(context, usb, device)) throw IOException(tr("Accès à l'adaptateur USB refusé"))
            }
            val connection = usb.openDevice(device) ?: throw IOException(tr("Impossible d'ouvrir l'adaptateur USB"))
            val port = driver.ports.first()
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.dtr = true; port.rts = true }
            onStep(tr("Port série ouvert à %1\$s bauds", baud))
            return SerialChannel(port)
        }

        private fun requestPermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
            val latch = CountDownLatch(1)
            var granted = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE))
                latch.await(60, TimeUnit.SECONDS)
            } finally {
                context.unregisterReceiver(receiver)
            }
            return granted
        }
    }
}
