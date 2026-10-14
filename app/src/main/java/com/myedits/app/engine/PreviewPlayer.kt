package com.myedits.app.engine

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.myedits.app.data.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live preview. It plays the exact [androidx.media3.transformer.Composition] that export uses
 * (minus burned-in overlays, which the UI draws interactively), and rebuilds it only when
 * something that affects pixels or audio actually changed.
 */
@UnstableApi
class PreviewPlayer(context: Context) {
    private val player: CompositionPlayer = CompositionPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    private var signature: Int? = null
    private var durationMs = 0L
    private var pendingSeekMs: Long? = null
    private var resumeAfterPrepare = false

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                _ended.value = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) {
                    pendingSeekMs?.let { player.seekTo(it); pendingSeekMs = null }
                    if (resumeAfterPrepare) { player.play(); resumeAfterPrepare = false }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _error.value = "Preview failed: ${error.errorCodeName}"
            }
        })
    }

    fun attach(surface: SurfaceView) = player.setVideoSurfaceView(surface)
    fun detach(surface: SurfaceView) = player.clearVideoSurfaceView(surface)

    val positionMs: Long get() = player.currentPosition
    val isPlaying: Boolean get() = player.isPlaying

    private fun signatureOf(p: Project): Int =
        p.copy(name = "", updatedAt = 0, exportCount = 0, texts = emptyList(), stickers = emptyList(), createdAt = 0).hashCode()

    /** Re-targets the player at [p]. [positionMs] is where to resume after the rebuild. */
    fun update(p: Project, positionMs: Long, shortSide: Int = 540) {
        val sig = signatureOf(p)
        if (sig == signature) return
        signature = sig
        _error.value = null
        durationMs = p.durationMs
        if (p.clips.isEmpty()) { player.stop(); return }
        val wasPlaying = player.isPlaying
        val (w, h) = CompositionBuilder.canvasSize(p.aspect, shortSide)
        runCatching {
            val comp = CompositionBuilder.build(p, RenderSpec(w, h, includeOverlays = false))
            player.setComposition(comp)
            pendingSeekMs = positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
            resumeAfterPrepare = wasPlaying
            player.prepare()
        }.onFailure { _error.value = "Preview failed: ${it.message}" }
    }

    fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() { resumeAfterPrepare = false; player.pause() }

    fun seekTo(ms: Long) {
        val t = ms.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        if (player.playbackState == Player.STATE_IDLE) pendingSeekMs = t else player.seekTo(t)
    }

    fun setScrubbing(on: Boolean) = runCatching { player.setScrubbingModeEnabled(on) }.let { }

    fun release() { runCatching { player.release() } }
}
