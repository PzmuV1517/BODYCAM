package com.bodycam.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wi-Fi recovery over Bluetooth LE. While the Wi-Fi link is down, connects to the Pi's BLE console
 * (Nordic UART Service), logs in with challenge/response (HMAC of the Wi-Fi password, never sent),
 * reads the AP status and restarts the AP if it is down. One short session, then disconnects so
 * Bluetooth doesn't compete with Wi-Fi on the Pi's shared radio.
 */
@SuppressLint("MissingPermission") // permissions checked in hasPermission()
class BleRescue(private val context: Context, private val report: (String) -> Unit) {

    @Volatile var busy = false
        private set

    private val thread = HandlerThread("bodycam-ble").apply { start() }
    private val h = Handler(thread.looper)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var buf = StringBuilder()
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false
    private var step = 0
    private var password = ""
    private var expectSsid = ""
    private var scanCb: ScanCallback? = null

    fun quit() {
        h.post { finish(null) }
        thread.quitSafely()
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    /** One check: scan, log in, read status, restart the AP if it's down. */
    fun check(password: String, expectSsid: String) {
        if (busy) return
        if (!hasPermission()) { report("BLE: NO PERMISSION"); return }
        if (adapter == null || !adapter.isEnabled) { report("BLE: OFF"); return }
        busy = true
        this.password = password
        this.expectSsid = expectSsid
        h.post { scan() }
        h.postDelayed({ if (busy) finish("BLE: NO PI") }, 25_000) // whole session timeout
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return finish("BLE: OFF")
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                h.post {
                    if (scanCb == null) return@post
                    stopScan()
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                h.post { finish("BLE: SCAN FAIL $errorCode") }
            }
        }
        scanCb = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        h.postDelayed({ if (scanCb != null) { stopScan(); finish("BLE: NO PI") } }, 10_000)
    }

    private fun stopScan() {
        scanCb?.let { try { adapter?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {} }
        scanCb = null
    }

    private fun connect(device: BluetoothDevice) {
        step = 0
        buf = StringBuilder()
        writes.clear()
        writing = false
        mtu = 23
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247)
                else if (busy) finish("BLE: DISCONNECTED")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            h.post { mtu = newMtu; g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                val svc = g.getService(NUS) ?: return@post finish("BLE: NO CONSOLE")
                rx = svc.getCharacteristic(RX)
                val tx = svc.getCharacteristic(TX) ?: return@post finish("BLE: NO CONSOLE")
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD) ?: return@post finish("BLE: NO CONSOLE")
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post { send("HELLO") }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post { writing = false; pump() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            h.post { onData(value) }
        }

        @Deprecated("pre-33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val v = c.value ?: return
                h.post { onData(v) }
            }
        }
    }

    private fun send(line: String) {
        val data = (line + "\n").toByteArray()
        val size = maxOf(20, mtu - 3)
        var i = 0
        while (i < data.size) {
            writes.addLast(data.copyOfRange(i, minOf(data.size, i + size))); i += size
        }
        pump()
    }

    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val c = rx ?: return
        val chunk = writes.removeFirstOrNull() ?: return
        writing = true
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = chunk
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun onData(bytes: ByteArray) {
        buf.append(String(bytes))
        while (true) {
            val i = buf.indexOf("<<END ")
            if (i < 0) return
            val j = buf.indexOf(">>\n", i)
            if (j < 0) return
            val text = buf.substring(0, i)
            val rc = buf.substring(i + 6, j).trim().toIntOrNull() ?: 1
            buf.delete(0, j + 3)
            onReply(rc, text)
        }
    }

    private fun onReply(rc: Int, text: String) {
        when (step) {
            0 -> { // NONCE
                val nonce = text.trim().split(" ").getOrNull(1) ?: return finish("BLE: BAD REPLY")
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password.toByteArray(), "HmacSHA256")) }
                val proof = mac.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
                step = 1
                send("AUTH $proof")
            }
            1 -> if (rc != 0) finish("BLE: LOGIN DENIED") else { step = 2; send("status") }
            2 -> {
                val kv = text.lines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                val ssid = kv["ssid"] ?: "?"
                if (kv["ap"] == "down") {
                    step = 3
                    report("BLE: AP DOWN, RESTARTING")
                    send("wifi restart")
                } else {
                    val mismatch = if (expectSsid.isNotBlank() && ssid != expectSsid) "  PI SSID $ssid" else ""
                    finish("BLE: AP UP CH${kv["channel"] ?: "?"}  ${kv["clients"] ?: "?"} CL$mismatch")
                }
            }
            3 -> finish(if (rc == 0) "BLE: AP RESTARTED" else "BLE: AP RESTART FAILED")
        }
    }

    private fun finish(result: String?) {
        stopScan()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        rx = null
        h.removeCallbacksAndMessages(null)
        if (busy && result != null) {
            Log.i(TAG, result)
            report(result)
        }
        busy = false
    }

    companion object {
        private const val TAG = "BleRescue"
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
