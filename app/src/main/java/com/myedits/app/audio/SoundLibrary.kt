package com.myedits.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

enum class SoundCategory(val label: String) { MUSIC("Music"), EFFECTS("Sound effects") }

data class SoundItem(val id: String, val label: String, val category: SoundCategory, val durationMs: Long, val emoji: String)

/**
 * Royalty-free sounds synthesised on the device (no downloads, no licensing): short effects and
 * a few loopable music beds. Files are rendered once to WAV and cached.
 */
object SoundLibrary {
    const val SAMPLE_RATE = 22_050

    val items: List<SoundItem> = listOf(
        SoundItem("music_lofi", "Lo-fi Chill", SoundCategory.MUSIC, 32_000, "☕"),
        SoundItem("music_pop", "Upbeat Pop", SoundCategory.MUSIC, 24_000, "🎉"),
        SoundItem("music_cinema", "Cinematic Pad", SoundCategory.MUSIC, 30_000, "🎬"),
        SoundItem("music_retro", "Retro Wave", SoundCategory.MUSIC, 24_000, "🕹️"),
        SoundItem("sfx_pop", "Pop", SoundCategory.EFFECTS, 300, "💥"),
        SoundItem("sfx_whoosh", "Whoosh", SoundCategory.EFFECTS, 900, "💨"),
        SoundItem("sfx_ding", "Ding", SoundCategory.EFFECTS, 1_400, "🔔"),
        SoundItem("sfx_chime", "Chime", SoundCategory.EFFECTS, 1_800, "✨"),
        SoundItem("sfx_drum", "Kick drum", SoundCategory.EFFECTS, 600, "🥁"),
        SoundItem("sfx_zap", "Zap", SoundCategory.EFFECTS, 500, "⚡"),
        SoundItem("sfx_clap", "Clap", SoundCategory.EFFECTS, 500, "👏"),
        SoundItem("sfx_riser", "Riser", SoundCategory.EFFECTS, 2_500, "📈"),
        SoundItem("sfx_heart", "Heartbeat", SoundCategory.EFFECTS, 1_600, "❤️"),
    )

    fun byId(id: String): SoundItem? = items.firstOrNull { it.id == id }

    /** Returns the cached WAV for [id], rendering it first if needed. */
    fun file(dir: File, id: String): File {
        dir.mkdirs()
        val f = File(dir, "$id.wav")
        if (f.exists() && f.length() > 44) return f
        val samples = render(id)
        val tmp = File(dir, "$id.wav.tmp")
        writeWav(tmp, samples)
        tmp.renameTo(f)
        return f
    }

    fun render(id: String): ShortArray {
        val item = byId(id) ?: error("Unknown sound $id")
        val n = (item.durationMs * SAMPLE_RATE / 1000).toInt()
        val buf = FloatArray(n)
        when (id) {
            "sfx_pop" -> pop(buf)
            "sfx_whoosh" -> whoosh(buf)
            "sfx_ding" -> bell(buf, 0f, 1318.5f, 1.3f)
            "sfx_chime" -> { bell(buf, 0f, 783.99f, 1.0f); bell(buf, 0.18f, 987.77f, 1.0f); bell(buf, 0.36f, 1318.5f, 1.2f) }
            "sfx_drum" -> kick(buf, 0f, 1f)
            "sfx_zap" -> zap(buf)
            "sfx_clap" -> clap(buf)
            "sfx_riser" -> riser(buf)
            "sfx_heart" -> { kick(buf, 0f, 0.9f); kick(buf, 0.28f, 0.6f); kick(buf, 0.9f, 0.9f); kick(buf, 1.18f, 0.6f) }
            "music_lofi" -> lofi(buf)
            "music_pop" -> pop4(buf)
            "music_cinema" -> cinema(buf)
            "music_retro" -> retro(buf)
        }
        return normalize(buf)
    }

    // ---- primitives ---------------------------------------------------------------------

    private fun idx(sec: Float) = (sec * SAMPLE_RATE).toInt()

    private fun midi(note: Int): Float = 440f * 2f.pow((note - 69) / 12f)

    private fun addTone(buf: FloatArray, startSec: Float, durSec: Float, freq: Float, amp: Float, wave: (Double) -> Double, attack: Float = 0.005f, release: Float = 0.08f) {
        val s = idx(startSec)
        val len = idx(durSec)
        var phase = 0.0
        val inc = freq / SAMPLE_RATE.toDouble()
        for (i in 0 until len) {
            val j = s + i
            if (j >= buf.size) break
            val t = i / SAMPLE_RATE.toFloat()
            val env = min(1f, t / attack) * min(1f, (durSec - t) / release).coerceAtLeast(0f)
            buf[j] += (wave(phase) * amp * env).toFloat()
            phase += inc
        }
    }

    private val sine: (Double) -> Double = { sin(2 * PI * it) }
    private val tri: (Double) -> Double = { val p = it - floor(it); 4 * abs(p - 0.5) - 1 }
    private val saw: (Double) -> Double = { val p = it - floor(it); 2 * p - 1 }
    private val square: (Double) -> Double = { val p = it - floor(it); if (p < 0.5) 0.6 else -0.6 }

    private fun kick(buf: FloatArray, atSec: Float, amp: Float) {
        val s = idx(atSec); val len = idx(0.35f)
        var phase = 0.0
        for (i in 0 until len) {
            val j = s + i; if (j >= buf.size) break
            val t = i / SAMPLE_RATE.toFloat()
            val f = 50 + 110 * exp(-t * 28)
            phase += f / SAMPLE_RATE
            buf[j] += (sin(2 * PI * phase) * exp(-t * 9) * amp).toFloat()
        }
    }

    private fun hat(buf: FloatArray, atSec: Float, amp: Float, rnd: Random) {
        val s = idx(atSec); val len = idx(0.05f)
        var prev = 0f
        for (i in 0 until len) {
            val j = s + i; if (j >= buf.size) break
            val n = rnd.nextFloat() * 2 - 1
            val hp = n - prev; prev = n
            buf[j] += hp * exp(-i / (SAMPLE_RATE * 0.012f)) * amp
        }
    }

    private fun snare(buf: FloatArray, atSec: Float, amp: Float, rnd: Random) {
        val s = idx(atSec); val len = idx(0.2f)
        for (i in 0 until len) {
            val j = s + i; if (j >= buf.size) break
            val t = i / SAMPLE_RATE.toFloat()
            buf[j] += ((rnd.nextFloat() * 2 - 1) * 0.7f * exp(-t * 20) + sin(2 * PI * 190 * t).toFloat() * 0.4f * exp(-t * 25)) * amp
        }
    }

    private fun pop(buf: FloatArray) {
        var phase = 0.0
        for (i in buf.indices) {
            val t = i / SAMPLE_RATE.toFloat()
            phase += (900 * exp(-t * 18) + 120) / SAMPLE_RATE
            buf[i] = (sin(2 * PI * phase) * exp(-t * 20)).toFloat()
        }
    }

    private fun whoosh(buf: FloatArray) {
        val rnd = Random(7); var lp = 0f
        for (i in buf.indices) {
            val p = i / buf.size.toFloat()
            val env = sin(PI * p).pow(2).toFloat()
            val k = 0.02f + 0.55f * sin(PI * p).toFloat()
            lp += k * ((rnd.nextFloat() * 2 - 1) - lp)
            buf[i] = lp * env * 1.6f
        }
    }

    private fun bell(buf: FloatArray, atSec: Float, freq: Float, decay: Float) {
        addTone(buf, atSec, decay, freq, 0.5f, sine, release = decay * 0.9f)
        addTone(buf, atSec, decay * 0.7f, freq * 2.01f, 0.25f, sine, release = decay * 0.6f)
        addTone(buf, atSec, decay * 0.4f, freq * 3.02f, 0.12f, sine, release = decay * 0.35f)
    }

    private fun zap(buf: FloatArray) {
        var phase = 0.0
        for (i in buf.indices) {
            val t = i / SAMPLE_RATE.toFloat()
            phase += (2200 * exp(-t * 7) + 150) / SAMPLE_RATE
            buf[i] = (saw(phase) * exp(-t * 6)).toFloat() * 0.8f
        }
    }

    private fun clap(buf: FloatArray) {
        val rnd = Random(3)
        for (burst in 0 until 3) {
            val s = idx(burst * 0.022f)
            for (i in 0 until idx(0.06f)) { val j = s + i; if (j < buf.size) buf[j] += (rnd.nextFloat() * 2 - 1) * exp(-i / (SAMPLE_RATE * 0.01f)) * 0.7f }
        }
        val s = idx(0.07f)
        for (i in 0 until idx(0.3f)) { val j = s + i; if (j < buf.size) buf[j] += (rnd.nextFloat() * 2 - 1) * exp(-i / (SAMPLE_RATE * 0.07f)) * 0.6f }
    }

    private fun riser(buf: FloatArray) {
        val rnd = Random(11); var phase = 0.0; var lp = 0f
        for (i in buf.indices) {
            val p = i / buf.size.toFloat()
            phase += (180 + 1400 * p * p) / SAMPLE_RATE
            lp += (0.05f + 0.5f * p) * ((rnd.nextFloat() * 2 - 1) - lp)
            buf[i] = ((sin(2 * PI * phase) * 0.35f + lp * 0.9f) * p.pow(1.5f)).toFloat()
        }
    }

    // ---- music beds ---------------------------------------------------------------------

    private fun lofi(buf: FloatArray) {
        val bpm = 78f; val beat = 60f / bpm; val rnd = Random(5)
        val chords = listOf(listOf(57, 60, 64, 67), listOf(53, 57, 60, 64), listOf(48, 52, 55, 59), listOf(55, 59, 62, 65))
        val bars = (buf.size / SAMPLE_RATE.toFloat() / (beat * 4)).toInt()
        for (bar in 0 until bars) {
            val chord = chords[bar % 4]; val t0 = bar * beat * 4
            chord.forEach { addTone(buf, t0, beat * 3.8f, midi(it), 0.11f, tri, attack = 0.04f, release = 0.4f) }
            addTone(buf, t0, beat * 1.9f, midi(chord[0] - 12), 0.25f, sine); addTone(buf, t0 + beat * 2, beat * 1.9f, midi(chord[0] - 12), 0.22f, sine)
            for (b in 0 until 4) {
                if (b % 2 == 0) kick(buf, t0 + b * beat, 0.7f)
                if (b % 2 == 1) snare(buf, t0 + b * beat, 0.35f, rnd)
                hat(buf, t0 + b * beat, 0.18f, rnd); hat(buf, t0 + b * beat + beat / 2, 0.1f, rnd)
            }
            val melody = listOf(0, 2, 3, 2, 0, -2, 0, 3)
            melody.forEachIndexed { i, step -> if (i % 2 == bar % 2) addTone(buf, t0 + i * beat / 2, beat * 0.45f, midi(chord[2] + 12 + step), 0.1f, sine) }
        }
    }

    private fun pop4(buf: FloatArray) {
        val bpm = 118f; val beat = 60f / bpm; val rnd = Random(9)
        val chords = listOf(listOf(60, 64, 67), listOf(67, 71, 74), listOf(69, 72, 76), listOf(65, 69, 72))
        val bars = (buf.size / SAMPLE_RATE.toFloat() / (beat * 4)).toInt()
        for (bar in 0 until bars) {
            val chord = chords[bar % 4]; val t0 = bar * beat * 4
            for (b in 0 until 4) {
                kick(buf, t0 + b * beat, 0.8f)
                if (b % 2 == 1) snare(buf, t0 + b * beat, 0.4f, rnd)
                hat(buf, t0 + b * beat + beat / 2, 0.22f, rnd)
                addTone(buf, t0 + b * beat, beat * 0.45f, midi(chord[0] - 24), 0.28f, saw, release = 0.05f)
            }
            for (i in 0 until 8) addTone(buf, t0 + i * beat / 2, beat * 0.4f, midi(chord[i % 3] + 12), 0.1f, square, release = 0.04f)
        }
    }

    private fun cinema(buf: FloatArray) {
        val chords = listOf(listOf(45, 52, 57, 60), listOf(41, 48, 53, 57), listOf(43, 50, 55, 59), listOf(40, 47, 52, 56))
        val barSec = 7.5f
        val bars = (buf.size / SAMPLE_RATE.toFloat() / barSec).toInt()
        for (bar in 0 until bars) {
            val t0 = bar * barSec
            chords[bar % 4].forEach { n ->
                addTone(buf, t0, barSec, midi(n), 0.12f, tri, attack = 2.2f, release = 2.5f)
                addTone(buf, t0, barSec, midi(n) * 1.004f, 0.08f, saw, attack = 2.4f, release = 2.5f)
            }
            addTone(buf, t0, barSec, midi(chords[bar % 4][0] - 12), 0.3f, sine, attack = 1.2f, release = 2f)
        }
    }

    private fun retro(buf: FloatArray) {
        val bpm = 100f; val beat = 60f / bpm; val rnd = Random(13)
        val roots = listOf(45, 41, 48, 43)
        val bars = (buf.size / SAMPLE_RATE.toFloat() / (beat * 4)).toInt()
        for (bar in 0 until bars) {
            val root = roots[bar % 4]; val t0 = bar * beat * 4
            for (i in 0 until 8) addTone(buf, t0 + i * beat / 2, beat * 0.48f, midi(root - 12), 0.26f, saw, release = 0.04f)
            val arp = listOf(0, 7, 12, 16, 12, 7, 12, 19)
            for (i in 0 until 16) addTone(buf, t0 + i * beat / 4, beat * 0.22f, midi(root + 12 + arp[i % 8]), 0.09f, square, release = 0.03f)
            for (b in 0 until 4) { kick(buf, t0 + b * beat, 0.75f); if (b % 2 == 1) snare(buf, t0 + b * beat, 0.4f, rnd); hat(buf, t0 + b * beat + beat / 2, 0.16f, rnd) }
        }
    }

    // ---- output ---------------------------------------------------------------------------

    private fun normalize(buf: FloatArray): ShortArray {
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val g = 0.85f / peak
        val out = ShortArray(buf.size)
        // short fade out to avoid a click at the very end
        val fade = min(buf.size, idx(0.01f))
        for (i in buf.indices) {
            var v = buf[i] * g
            if (i >= buf.size - fade) v *= (buf.size - i) / fade.toFloat()
            out[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun writeWav(file: File, samples: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val dataLen = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen)
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { body.putShort(it) }
            raf.write(body.array())
        }
    }
}
