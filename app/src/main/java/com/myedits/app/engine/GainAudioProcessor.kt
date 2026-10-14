package com.myedits.app.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure gain curve so the maths can be unit tested without any audio plumbing. */
object GainCurve {
    /** Linear gain at [posMs] inside a segment [totalMs] long, with linear fade in/out ramps. */
    fun gainAt(posMs: Double, totalMs: Long, volume: Float, fadeInMs: Long, fadeOutMs: Long): Float {
        var g = volume
        if (fadeInMs > 0 && posMs < fadeInMs) g *= (posMs / fadeInMs).toFloat().coerceIn(0f, 1f)
        if (fadeOutMs > 0 && totalMs > 0) {
            val remaining = totalMs - posMs
            if (remaining < fadeOutMs) g *= (remaining / fadeOutMs).toFloat().coerceIn(0f, 1f)
        }
        return g
    }
}

/**
 * Applies a constant volume plus optional fade-in / fade-out to 16-bit PCM.
 * Place it after any speed-change processor so [totalMs] is measured in output time.
 */
@UnstableApi
class GainAudioProcessor(
    private val volume: Float,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val totalMs: Long,
) : BaseAudioProcessor() {

    private var framesSeen = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        super.isActive() && (volume != 1f || fadeInMs > 0 || fadeOutMs > 0)

    override fun onFlush() {
        framesSeen = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate.toDouble()
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = size / (2 * channels)
        val simple = fadeInMs == 0L && fadeOutMs == 0L
        val constGain = GainCurve.gainAt(0.0, totalMs, volume, 0, 0)
        for (f in 0 until frames) {
            val g = if (simple) constGain else GainCurve.gainAt((framesSeen + f) * 1000.0 / rate, totalMs, volume, fadeInMs, fadeOutMs)
            for (c in 0 until channels) {
                val s = inp.getShort()
                out.putShort((s * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        // Any trailing partial frame is passed through untouched so the input is always fully consumed.
        while (inp.hasRemaining()) out.put(inp.get())
        framesSeen += frames
        out.flip()
    }
}
