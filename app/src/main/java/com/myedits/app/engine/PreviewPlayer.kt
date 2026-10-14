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
/** What the editor needs from a preview. The real implementation is [PreviewPlayer]. */
interface PreviewController {
    val playing: StateFlow<Boolean>
    val error: StateFlow<String?>
    val ended: StateFlow<Boolean>
    val positionMs: Long
    val isPlaying: Boolean
    fun attach(surface: SurfaceView)
    fun detach(surface: SurfaceView)
    fun update(p: Project, positionMs: Long, shortSide: Int = 540)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setScrubbing(on: Boolean)
    fun release()
}

@UnstableApi
class PreviewPlayer(context: Context) : PreviewController {
    private val player: CompositionPlayer = CompositionPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()

    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _ended = MutableStateFlow(false)
    override val ended: StateFlow<Boolean> = _ended.asStateFlow()

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

    override fun attach(surface: SurfaceView) = player.setVideoSurfaceView(surface)
    override fun detach(surface: SurfaceView) = player.clearVideoSurfaceView(surface)

    override val positionMs: Long get() = player.currentPosition
    override val isPlaying: Boolean get() = player.isPlaying

    private fun signatureOf(p: Project): Int =
        p.copy(name = "", updatedAt = 0, exportCount = 0, texts = emptyList(), stickers = emptyList(), createdAt = 0).hashCode()

    /** Re-targets the player at [p]. [positionMs] is where to resume after the rebuild. */
    override fun update(p: Project, positionMs: Long, shortSide: Int) {
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

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() { resumeAfterPrepare = false; player.pause() }

    override fun seekTo(ms: Long) {
        val t = ms.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        if (player.playbackState == Player.STATE_IDLE) pendingSeekMs = t else player.seekTo(t)
    }

    override fun setScrubbing(on: Boolean) = runCatching { player.setScrubbingModeEnabled(on) }.let { }

    override fun release() { runCatching { player.release() } }
}
