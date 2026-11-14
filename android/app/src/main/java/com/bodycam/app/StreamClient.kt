package com.bodycam.app

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * UDP link to the Pi. Sends HELLO heartbeats at 10 Hz, reassembles video frames, parses
 * detections/status. No handshake: as soon as packets flow, video flows.
 *
 * Frames are only forwarded from a keyframe onwards; any loss -> stop forwarding and keep asking
 * for a keyframe until one arrives (so the decoder never sees a broken reference chain).
 */
class StreamClient(
    private val host: String,
    private val port: Int,
    private val network: Network?,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun onFrame(data: ByteArray, key: Boolean, captureMs: Long) // rx thread
        fun onDetections(boxes: List<Box>)         // rx thread
        fun onStatus(status: PiStatus, rttMs: Int) // rx thread
    }

    @Volatile var wantAi = true
    @Volatile var wantFan = 255 // 0/1 while the user is changing the Pi's fan signal setting
    @Volatile var wantNight = 255 // same for night exposure
    @Volatile var wantOverride = 255 // fan override: 0 = auto, 1..100 %, 255 = leave

    /** Pi clock minus phone clock (elapsedRealtime), ms mod 2^32; from the fastest status replies. */
    @Volatile var clockOffsetMs: Long? = null
        private set
    private val offsetSamples = ArrayDeque<Pair<Int, Long>>() // (rtt, offset)
    @Volatile private var running = true
    @Volatile private var needKey = true
    @Volatile private var lastKeyReq = 0L

    // pending SET_WIFI (resent by tx thread until acked or timed out)
    private class WifiReq(val id: Int, val packet: ByteArray, val deadline: Long, val done: (Boolean, String) -> Unit)
    @Volatile private var wifiReq: WifiReq? = null

    /** Ask the Pi to change its AP SSID/password. [done] runs on the rx/tx thread. */
    fun setPiWifi(ssid: String, pass: String, done: (Boolean, String) -> Unit) {
        val id = (SystemClock.elapsedRealtime() and 0x7FFFFFFF).toInt()
        wifiReq = WifiReq(id, Proto.setWifi(id, ssid, pass), SystemClock.elapsedRealtime() + 4000, done)
    }

    private val socket = DatagramSocket()
    private val sentAt = LongArray(64)

    // reassembly state (rx thread only)
    private var curId = -1L
    private var parts: Array<ByteArray?> = emptyArray()
    private var got = 0
    private var curKey = false
    private var lastDoneId = -1L

    init {
        network?.bindSocket(socket) // route over the Pi's Wi-Fi even if it has no internet
        socket.soTimeout = 250
        socket.receiveBufferSize = 1 shl 21
        socket.trafficClass = 0xB8 // DSCP EF -> WMM video queue
    }

    private val tx = thread(name = "bodycam-tx", isDaemon = true) { txLoop() }
    private val rx = thread(name = "bodycam-rx", isDaemon = true) { rxLoop() }

    fun close() {
        running = false
        socket.close()
    }

    /** Called when the decoder had to drop something: resync on the next keyframe. */
    fun requestKeyframe() {
        needKey = true
    }

    private fun txLoop() {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            Log.e(TAG, "bad host $host", e); return
        }
        var seq = 0
        while (running) {
            try {
                seq++
                sentAt[seq and 63] = SystemClock.elapsedRealtime()
                val hello = Proto.hello(seq, wantAi, wantFan, wantNight, wantOverride)
                socket.send(DatagramPacket(hello, hello.size, addr, port))
                val now = SystemClock.elapsedRealtime()
                wifiReq?.let { r ->
                    if (now > r.deadline) {
                        wifiReq = null; r.done(false, "Pi didn't answer")
                    } else {
                        socket.send(DatagramPacket(r.packet, r.packet.size, addr, port))
                    }
                }
                if (needKey && now - lastKeyReq > 300) { // retried until a keyframe shows up
                    lastKeyReq = now
                    socket.send(DatagramPacket(byteArrayOf(Proto.KEYFRAME_REQ.toByte()), 1, addr, port))
                }
            } catch (e: Exception) {
                if (!running) return
            }
            Thread.sleep(100)
        }
    }

    private fun rxLoop() {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.setLength(buf.size)
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) return
                Thread.sleep(50); continue
            }
            val n = pkt.length
            if (n < 1) continue
            try {
                when (Proto.u8(buf, 0)) {
                    Proto.VIDEO -> if (n > Proto.VIDEO_HDR) onVideo(buf, n)
                    Proto.DETECT -> if (n >= Proto.DETECT_HDR) onDetect(buf, n)
                    Proto.STATUS -> if (n >= Proto.STATUS_SIZE) onStatus(buf, n)
                    Proto.WIFI_ACK -> if (n >= 7) onWifiAck(buf, n)
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad packet", e)
            }
        }
    }

    private fun onVideo(b: ByteArray, n: Int) {
        val id = Proto.u32(b, 1)
        val idx = Proto.u16(b, 5)
        val count = Proto.u16(b, 7)
        val key = (Proto.u8(b, 9) and 1) != 0
        if (count == 0 || idx >= count) return
        val captureMs = Proto.u32(b, 10)

        if (id != curId) {
            if (id < curId && curId - id < 1000 && !key) return // late packet of an old frame (keyframes always win: Pi may have restarted)
            if (curId >= 0 && got in 1 until parts.size) lost() // previous frame never completed
            curId = id
            parts = arrayOfNulls(count)
            got = 0
            curKey = key
        }
        if (got >= parts.size || parts[idx] != null) return
        parts[idx] = b.copyOfRange(Proto.VIDEO_HDR, n)
        got++
        if (got < parts.size) return

        // complete access unit
        if (lastDoneId >= 0 && id != lastDoneId + 1 && !curKey) lost() // whole frame(s) vanished
        lastDoneId = id
        if (curKey) needKey = false
        if (needKey) return
        var size = 0
        for (p in parts) size += p!!.size
        val frame = ByteArray(size)
        var o = 0
        for (p in parts) {
            System.arraycopy(p!!, 0, frame, o, p.size); o += p.size
        }
        cb.onFrame(frame, curKey, captureMs)
    }

    private fun lost() {
        needKey = true
    }

    private fun onDetect(b: ByteArray, n: Int) {
        val count = Proto.u8(b, 6)
        val boxes = ArrayList<Box>(count)
        var o = Proto.DETECT_HDR
        repeat(count) {
            if (o + Proto.BOX_SIZE > n) return@repeat
            boxes += Box(
                Proto.u16(b, o) / 65535f, Proto.u16(b, o + 2) / 65535f,
                Proto.u16(b, o + 4) / 65535f, Proto.u16(b, o + 6) / 65535f,
                Proto.u8(b, o + 8) / 255f,
            )
            o += Proto.BOX_SIZE
        }
        cb.onDetections(boxes)
    }

    private fun onWifiAck(b: ByteArray, n: Int) {
        val r = wifiReq ?: return
        if (Proto.u32(b, 1).toInt() != r.id) return
        wifiReq = null
        val len = minOf(Proto.u8(b, 6), n - 7)
        r.done(Proto.u8(b, 5) == 1, String(b, 7, len))
    }

    private fun onStatus(b: ByteArray, n: Int) {
        val seq = Proto.u32(b, 1).toInt()
        val recv = SystemClock.elapsedRealtime()
        val rtt = (recv - sentAt[seq and 63]).toInt().coerceIn(0, 9999)
        var fanInd: Boolean? = null
        val night: Boolean? = if (n >= Proto.STATUS_EXT2) Proto.u8(b, 15) != 0 else null
        val ovr: Int? = if (n >= Proto.STATUS_EXT3) Proto.u8(b, 16) else null
        val rpm: Int? = if (n >= Proto.STATUS_EXT3) Proto.u16(b, 17) else null
        if (n >= Proto.STATUS_EXT) {
            // Clock sync: Pi time at reply ~= phone time at receive - rtt/2. Keep the sample with
            // the smallest RTT (least queueing) out of the last 50.
            val piNow = Proto.u32(b, 10)
            val offset = (piNow + rtt / 2 - recv) and 0xFFFFFFFFL
            offsetSamples.addLast(rtt to offset)
            if (offsetSamples.size > 50) offsetSamples.removeFirst()
            clockOffsetMs = offsetSamples.minBy { it.first }.second
            fanInd = Proto.u8(b, 14) != 0
        }
        cb.onStatus(
            PiStatus(
                aiEnabled = Proto.u8(b, 5) != 0,
                hailoOk = Proto.u8(b, 6) != 0,
                encFps = Proto.u8(b, 7),
                inferFps = Proto.u8(b, 8),
                tempC = Proto.u8(b, 9),
                fanIndicator = fanInd,
                nightExposure = night,
                fanOverride = ovr,
                fanRpm = rpm,
            ),
            rtt,
        )
    }

    companion object {
        private const val TAG = "StreamClient"
    }
}
