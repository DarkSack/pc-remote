package com.sack.pcremote.ui.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.sack.pcremote.net.VideoSink
import java.nio.ByteBuffer
import java.util.ArrayDeque

// ══════════════════════════════════════════════════════════════
// H.264 → Surface con MediaCodec (decodificador por hardware).
//
// Baja latencia: cada fotograma se pinta en cuanto sale del decodificador
// (sin sincronizar con un reloj), KEY_LOW_LATENCY en Android 11+, y si se
// acumulan fotogramas sin decodificar se tiran hasta el siguiente clave.
//
// Un decodificador nuevo (al abrir, al rotar la superficie, tras un error)
// solo puede empezar en un fotograma clave: hasta entonces descarta los
// demás y pide uno al PC.
// ══════════════════════════════════════════════════════════════

class VideoDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val requestKeyFrame: () -> Unit,
) : VideoSink {

    private val thread = HandlerThread("PcRemote video").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val pending = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null
    @Volatile private var released = false
    private var lastKeyRequest = 0L

    /** Frames decoded since start, for the "first image" state. */
    @Volatile var framesRendered = 0L
        private set

    var onFirstFrame: (() -> Unit)? = null

    private class Frame(val data: ByteArray, val key: Boolean, val ptsUs: Long)

    override fun onFrame(data: ByteBuffer, keyFrame: Boolean, timestampUs: Long) {
        if (released) return
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        synchronized(lock) {
            if (codec == null && !configuring) {
                if (!keyFrame) { askKeyFrame(); return }
                configuring = true
                handler.post { configure(bytes) }
            }
            // Behind (slow decoder, burst after a stall): skip to the next key frame.
            if (pending.size > 8) {
                pending.clear()
                if (!keyFrame) { askKeyFrame(); dropping = true; return }
            }
            if (dropping && !keyFrame) return
            dropping = false
            pending.add(Frame(bytes, keyFrame, timestampUs))
        }
        handler.post { feed() }
    }

    private var dropping = false

    /** A key frame arrived and the codec is being set up: later frames queue behind it. */
    private var configuring = false

    private fun askKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest > 500) {
            lastKeyRequest = now
            requestKeyFrame()
        }
    }

    /** Starts the codec with the SPS/PPS of the first key frame (some decoders insist on csd). */
    private fun configure(keyFrame: ByteArray) {
        if (released || codec != null) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 512 * 1024))
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                parameterSets(keyFrame)?.let { (sps, pps) ->
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    synchronized(lock) { freeInputs.add(index) }
                    feed()
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    runCatching { codec.releaseOutputBuffer(index, true) }
                    if (framesRendered++ == 0L) onFirstFrame?.invoke()
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "Decoder error: ${e.diagnosticInfo}")
                    restart()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, handler)
            c.configure(format, surface, null, 0)
            c.start()
            synchronized(lock) { codec = c; configuring = false }
            Log.i(TAG, "Decoder ${c.name} ${width}x$height")
        } catch (e: Exception) {
            Log.w(TAG, "Decoder configure failed", e)
            synchronized(lock) { pending.clear(); configuring = false }
        }
    }

    private fun feed() {
        while (true) {
            val (c, index, frame) = synchronized(lock) {
                val c = codec ?: return
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                Triple(c, freeInputs.poll()!!, pending.poll()!!)
            }
            try {
                val buf = c.getInputBuffer(index) ?: continue
                buf.clear()
                if (frame.data.size > buf.capacity()) {
                    // Should not happen with KEY_MAX_INPUT_SIZE = w*h; recover with a key frame.
                    c.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                    askKeyFrame()
                    continue
                }
                buf.put(frame.data)
                c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, if (frame.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (e: Exception) {
                Log.w(TAG, "queueInputBuffer failed", e)
                restart()
                return
            }
        }
    }

    /** After a codec error: drop it, wait for a key frame, build a new one. */
    private fun restart() {
        handler.post {
            synchronized(lock) {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                codec = null
                configuring = false
                pending.clear()
                freeInputs.clear()
            }
            askKeyFrame()
        }
    }

    fun release() {
        released = true
        handler.post {
            synchronized(lock) {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                codec = null
                pending.clear()
                freeInputs.clear()
            }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"

        /** SPS and PPS (with start codes) of an Annex B access unit. */
        fun parameterSets(au: ByteArray): Pair<ByteArray, ByteArray>? {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val starts = mutableListOf<Int>()
            var i = 0
            while (i + 3 <= au.size) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) { starts.add(i + 3); i += 3 } else i++
            }
            for ((n, start) in starts.withIndex()) {
                var end = if (n + 1 < starts.size) starts[n + 1] - 3 else au.size
                while (end > start && au[end - 1].toInt() == 0 && n + 1 < starts.size) end--
                if (start >= end) continue
                val nal = byteArrayOf(0, 0, 0, 1) + au.copyOfRange(start, end)
                when (au[start].toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
            return if (sps != null && pps != null) sps to pps else null
        }
    }
}
