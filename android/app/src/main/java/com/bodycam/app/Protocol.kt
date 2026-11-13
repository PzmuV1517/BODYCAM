package com.bodycam.app

/** Wire protocol shared with pi/bodycam/server.py — see pi/README.md. All big-endian. */
object Proto {
    const val PORT = 5600
    const val VIDEO = 0x01
    const val DETECT = 0x02
    const val STATUS = 0x03
    const val HELLO = 0x10
    const val KEYFRAME_REQ = 0x11
    const val SET_WIFI = 0x12
    const val WIFI_ACK = 0x04

    const val VIDEO_HDR = 10   // u8 type, u32 frame_id, u16 idx, u16 count, u8 flags
    const val DETECT_HDR = 7   // u8 type, u32 frame_id, u8 ai, u8 count
    const val BOX_SIZE = 9     // u16 x1, y1, x2, y2, u8 score
    const val STATUS_SIZE = 10 // u8 type, u32 seq, u8 ai, hailo_ok, enc_fps, infer_fps, temp

    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    fun u32(b: ByteArray, o: Int) =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

    fun setWifi(reqId: Int, ssid: String, pass: String): ByteArray {
        val s = ssid.toByteArray()
        val p = pass.toByteArray()
        return byteArrayOf(
            SET_WIFI.toByte(),
            (reqId ushr 24).toByte(), (reqId ushr 16).toByte(), (reqId ushr 8).toByte(), reqId.toByte(),
            s.size.toByte(),
        ) + s + byteArrayOf(p.size.toByte()) + p
    }

    fun hello(seq: Int, wantAi: Boolean) = byteArrayOf(
        HELLO.toByte(),
        (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
        if (wantAi) 1 else 0,
    )
}

/** Person box, coordinates 0..1 of the video frame. */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float)

data class PiStatus(
    val aiEnabled: Boolean,
    val hailoOk: Boolean,
    val encFps: Int,
    val inferFps: Int,
    val tempC: Int,
)
