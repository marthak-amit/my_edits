package com.myedits.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Thin wrapper over MediaRecorder for voiceovers (AAC in an .m4a container). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(target: File): Result<Unit> = runCatching {
        check(recorder == null) { "Already recording" }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            target.delete()
            throw e
        }
        recorder = r; file = target; startedAt = System.currentTimeMillis()
    }

    /** 0..1 input level for the on-screen meter. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    fun elapsedMs(): Long = if (recorder == null) 0L else System.currentTimeMillis() - startedAt

    /** Stops and returns the recorded file, or null if nothing usable was captured. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val f = file; file = null
        val ok = runCatching { r.stop() }.isSuccess // throws if stopped immediately
        runCatching { r.release() }
        return if (ok && f != null && f.length() > 0) f else { f?.delete(); null }
    }

    fun cancel() { stop()?.delete() }
}
