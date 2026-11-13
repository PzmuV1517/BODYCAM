package com.bodycam.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hardware H.264 decoder rendering straight to a Surface, tuned for latency:
 * low-latency mode, tiny input queue, every output frame rendered immediately.
 * If it falls behind it drops everything and asks for a keyframe instead of building delay.
 */
class VideoDecoder(
    private val surface: Surface,
    private val onNeedKeyframe: () -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
) {
    private class Frame(val data: ByteArray, val key: Boolean)

    private val queue = ArrayBlockingQueue<Frame>(6)
    @Volatile private var running = true
    @Volatile private var waitKey = true
    private val worker = thread(name = "bodycam-decoder", isDaemon = true) { loop() }

    fun submit(data: ByteArray, key: Boolean) {
        if (waitKey && !key) return
        if (key) waitKey = false
        if (!queue.offer(Frame(data, key))) { // decoder is behind: drop all, resync, stay live
            queue.clear()
            waitKey = true
            onNeedKeyframe()
        }
    }

    fun release() {
        running = false
        worker.join(1000)
    }

    private fun createCodec(): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        // Vendor low-latency switches; ignored where unsupported.
        fmt.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
        fmt.setInteger("vendor.low-latency.enable", 1)
        fmt.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(fmt, surface, null, 0)
        } catch (e: Exception) { // some decoders reject unknown vendor keys: retry plain
            codec.reset()
            val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            plain.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            if (Build.VERSION.SDK_INT >= 30) plain.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec.configure(plain, surface, null, 0)
        }
        codec.start()
        return codec
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        while (running) {
            var codec: MediaCodec? = null
            try {
                codec = createCodec()
                waitKey = true
                queue.clear()
                onNeedKeyframe()
                while (running) {
                    val f = queue.poll(10, TimeUnit.MILLISECONDS)
                    if (f != null) {
                        val idx = codec.dequeueInputBuffer(20_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(f.data)
                            pts += 33_333
                            codec.queueInputBuffer(idx, 0, f.data.size, pts, 0)
                        } else {
                            queue.clear(); waitKey = true; onNeedKeyframe()
                        }
                    }
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 0)
                        when {
                            out >= 0 -> codec.releaseOutputBuffer(out, true)
                            out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val of = codec.outputFormat
                                val w = of.getInteger(MediaFormat.KEY_WIDTH)
                                val h = of.getInteger(MediaFormat.KEY_HEIGHT)
                                onVideoSize(w, h)
                            }
                            else -> break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "decoder error, restarting", e)
                if (!surface.isValid) running = false
                else Thread.sleep(100)
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
