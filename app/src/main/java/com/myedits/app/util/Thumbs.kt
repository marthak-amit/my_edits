package com.myedits.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** In-memory frame cache for timeline strips, filter swatches and project covers. */
@OptIn(ExperimentalCoroutinesApi::class)
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    private fun key(path: String, timeMs: Long, h: Int) = "$path@$timeMs@$h"

    fun cached(path: String, timeMs: Long, heightPx: Int): Bitmap? = cache.get(key(path, timeMs, heightPx))

    suspend fun frame(path: String, isImage: Boolean, timeMs: Long, heightPx: Int): Bitmap? {
        val k = key(path, if (isImage) 0L else timeMs, heightPx)
        cache.get(k)?.let { return it }
        val bmp = withContext(dispatcher) {
            runCatching { if (isImage) image(path, heightPx) else video(path, timeMs, heightPx) }.getOrNull()
        } ?: return null
        cache.put(k, bmp)
        return bmp
    }

    private fun image(path: String, heightPx: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        if (b.outHeight <= 0) return null
        var sample = 1
        while (b.outHeight / (sample * 2) >= heightPx) sample *= 2
        val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return scaleToHeight(raw, heightPx)
    }

    private fun video(path: String, timeMs: Long, heightPx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val raw = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            scaleToHeight(raw, heightPx)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun scaleToHeight(raw: Bitmap, heightPx: Int): Bitmap {
        if (raw.height <= heightPx) return raw
        val w = max(1, (raw.width * heightPx.toFloat() / raw.height).toInt())
        return Bitmap.createScaledBitmap(raw, w, heightPx, true).also { if (it !== raw) raw.recycle() }
    }
}

/** Real amplitude peaks (0..1) for the audio waveform on the timeline. */
object Waveforms {
    private val cache = HashMap<String, FloatArray>()
    const val BUCKET_MS = 100L

    suspend fun peaks(path: String, durationMs: Long): FloatArray {
        synchronized(cache) { cache[path] }?.let { return it }
        val result = withContext(Dispatchers.IO) { runCatching { decode(path, durationMs) }.getOrNull() }
            ?: FloatArray((durationMs / BUCKET_MS).toInt().coerceAtLeast(1)) { 0.15f }
        synchronized(cache) { cache[path] = result }
        return result
    }

    private fun decode(path: String, durationMs: Long): FloatArray {
        val buckets = (durationMs / BUCKET_MS).toInt().coerceAtLeast(1)
        val peaks = FloatArray(buckets)
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        if (track < 0) { ex.release(); return peaks }
        val fmt = ex.getTrackFormat(track)
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var frameIndex = 0L
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                when {
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = info.size / 2 / max(1, channels)
                        for (f in 0 until frames) {
                            var m = 0
                            for (c in 0 until channels) m = max(m, abs(out.get(f * channels + c).toInt()))
                            val b = ((frameIndex + f) * 1000L / rate / BUCKET_MS).toInt()
                            if (b in 0 until buckets) peaks[b] = max(peaks[b], m / 32768f)
                        }
                        frameIndex += frames
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; ex.release()
        }
        val top = peaks.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f
        for (i in peaks.indices) peaks[i] = (peaks[i] / top).coerceIn(0.04f, 1f)
        return peaks
    }
}
