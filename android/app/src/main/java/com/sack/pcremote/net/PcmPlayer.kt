package com.sack.pcremote.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

// ══════════════════════════════════════════════════════════════
// El sonido del PC mientras se mira la pantalla: PCM de 16 bits en
// estéreo, tal cual llega por el socket de la pantalla (mensaje 0x04).
//
// Baja latencia antes que continuidad: el búfer es de ~150 ms y se
// escribe sin bloquear, así que si la red trae una ráfaga lo que no
// cabe se tira en vez de acumular retraso respecto al vídeo.
// ══════════════════════════════════════════════════════════════

class PcmPlayer(sampleRate: Int) {
    private val track: AudioTrack = run {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4 * 150 / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .apply { play() }
    }

    /** Called on OkHttp's reader thread; never blocks it. */
    fun write(pcm: ByteArray, offset: Int, length: Int) {
        track.write(pcm, offset, length and 3.inv(), AudioTrack.WRITE_NON_BLOCKING)
    }

    fun release() {
        runCatching { track.pause(); track.flush() }
        track.release()
    }
}
