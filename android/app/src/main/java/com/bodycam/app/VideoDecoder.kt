package com.bodycam.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
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
    private val rotation: Int, // 0 or 180, applied by the codec at no cost
    private val onNeedKeyframe: () -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    /** (captureMs, displayedAt in elapsedRealtime ms) for each frame that reached the screen. */
    private val onDisplayed: (Long, Long) -> Unit,
) {
    private class Frame(val data: ByteArray, val key: Boolean, val captureMs: Long)
    @Volatile private var lastRenderCallback = 0L

    // The render callback's clock base differs between devices (on some it's not System.nanoTime),
    // so calibrate it: a frame can't be shown before we release it, hence
    // offset >= releaseTime - renderTime for every frame; the max over recent frames is tight.
    private val releasedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>() // ptsUs -> release ns (elapsedRealtime base)
    private val calib = ArrayDeque<Long>()
    private fun renderedAt(ptsUs: Long, renderNs: Long): Long? {
        val rel = releasedAt.remove(ptsUs) ?: return null
        synchronized(calib) {
            calib.addLast(rel - renderNs)
            if (calib.size > 60) calib.removeFirst()
            return (renderNs + calib.max()) / 1_000_000
        }
    }

    private val queue = ArrayBlockingQueue<Frame>(6)
    @Volatile private var running = true
    @Volatile private var waitKey = true
    private val worker = thread(name = "bodycam-decoder", isDaemon = true) { loop() }

    fun submit(data: ByteArray, key: Boolean, captureMs: Long) {
        if (waitKey && !key) return
        if (key) waitKey = false
        if (!queue.offer(Frame(data, key, captureMs))) { // decoder is behind: drop all, resync, stay live
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
        fmt.setInteger(MediaFormat.KEY_ROTATION, rotation)
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
            plain.setInteger(MediaFormat.KEY_ROTATION, rotation)
            if (Build.VERSION.SDK_INT >= 30) plain.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec.configure(plain, surface, null, 0)
        }
        // Exact on-screen time of each frame (System.nanoTime base) -> delay readout.
        codec.setOnFrameRenderedListener({ _, ptsUs, nanoTime ->
            lastRenderCallback = SystemClock.elapsedRealtime()
            renderedAt(ptsUs, nanoTime)?.let { onDisplayed(ptsUs / 1000, it) }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        codec.start()
        return codec
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
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
                            // pts carries the Pi capture time; it comes back with the rendered frame
                            codec.queueInputBuffer(idx, 0, f.data.size, f.captureMs * 1000, 0)
                        } else {
                            queue.clear(); waitKey = true; onNeedKeyframe()
                        }
                    }
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 0)
                        when {
                            out >= 0 -> {
                                releasedAt[info.presentationTimeUs] = SystemClock.elapsedRealtimeNanos()
                                if (releasedAt.size > 120) releasedAt.clear()
                                codec.releaseOutputBuffer(out, true)
                                // Fallback if this decoder never reports render times: ~1 vsync later.
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastRenderCallback > 2000) onDisplayed(info.presentationTimeUs / 1000, now + 16)
                            }
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
