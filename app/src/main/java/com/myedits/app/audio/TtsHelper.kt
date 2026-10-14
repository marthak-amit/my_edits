package com.myedits.app.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Renders text to a WAV file with the platform text-to-speech engine. */
class TtsHelper(private val context: Context) {
    class TtsException(message: String) : Exception(message)

    suspend fun synthesize(text: String, target: File, pitch: Float = 1f, rate: Float = 1f): Result<File> =
        suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            fun finish(r: Result<File>) {
                runCatching { tts?.shutdown() }
                if (cont.isActive) cont.resume(r)
            }
            tts = TextToSpeech(context) { status ->
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    finish(Result.failure(TtsException("No text-to-speech engine is available on this device"))); return@TextToSpeech
                }
                val lang = engine.setLanguage(Locale.getDefault()).let { if (it < 0) engine.setLanguage(Locale.US) else it }
                if (lang < 0) { finish(Result.failure(TtsException("Speech language not supported"))); return@TextToSpeech }
                engine.setPitch(pitch); engine.setSpeechRate(rate)
                val id = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (target.exists() && target.length() > 44) finish(Result.success(target))
                        else finish(Result.failure(TtsException("Speech synthesis produced no audio")))
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finish(Result.failure(TtsException("Speech synthesis failed"))) }
                })
                val r = engine.synthesizeToFile(text, Bundle(), target, id)
                if (r != TextToSpeech.SUCCESS) finish(Result.failure(TtsException("Speech synthesis failed")))
            }
            cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
        }
}
