package com.myedits.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    /** Frees codecs (e.g. while exporting). The next [update] re-prepares the preview. */
    fun suspend()
    fun release()
}

private const val TAG = "EditsPreview"
private const val MAX_RETRIES = 2
private const val REBUILD_DEBOUNCE_MS = 160L
private const val END_POLL_MS = 40L
private const val END_SLACK_MS = 25L

@UnstableApi
class PreviewPlayer(private val context: Context) : PreviewController {
    private lateinit var player: CompositionPlayer
    private var surfaceView: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * What the UI shows. This is the user's *intent* (play was requested and nothing has stopped it), not whether
     * frames are rendering right now, so the button flips instantly and never lies while the pipeline is buffering.
     */
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _ended = MutableStateFlow(false)
    override val ended: StateFlow<Boolean> = _ended.asStateFlow()

    private var wantPlay = false
    private var signature: Int? = null
    private var lastProject: Project? = null
    private var lastShortSide = 540
    private var lastPositionMs = 0L
    private var pendingUpdate: Runnable? = null
    private var retries = 0
    private var durationMs = 0L
    private var pendingSeekMs: Long? = null

    private fun setWantPlay(v: Boolean) {
        wantPlay = v; _playing.value = v
        handler.removeCallbacks(endWatch)
        if (v) handler.postDelayed(endWatch, END_POLL_MS)
    }

    /**
     * CompositionPlayer keeps "playing" past the last frame on some pipelines (position runs on, state never becomes
     * ENDED), which left the button showing pause over a frozen picture. Watch the clock and end playback ourselves.
     */
    private val endWatch = object : Runnable {
        override fun run() {
            if (!wantPlay) return
            if (durationMs > 0 && player.currentPosition >= durationMs - END_SLACK_MS && player.playbackState == Player.STATE_READY) {
                Log.d(TAG, "end reached at ${player.currentPosition} / $durationMs")
                setWantPlay(false)
                player.pause()
                runCatching { player.seekTo((durationMs - 1).coerceAtLeast(0L)) }
                _ended.value = true
            } else handler.postDelayed(this, END_POLL_MS)
        }
    }

    private fun newPlayer(): CompositionPlayer =
        CompositionPlayer.Builder(context).setLooper(Looper.getMainLooper()).build().also { it.addListener(listener) }

    /**
     * After a graph / codec failure the old CompositionPlayer can stay wedged (black preview, buttons dead), so recovery
     * throws it away and starts a fresh one on the same surface.
     */
    private fun recreatePlayer() {
        val old = player
        runCatching { surfaceView?.let(old::clearVideoSurfaceView) }
        runCatching { old.release() }
        player = newPlayer()
        surfaceView?.let { runCatching { player.setVideoSurfaceView(it) } }
    }

    private val listener: Player.Listener = run {
        object : Player.Listener {
            override fun onRenderedFirstFrame() { Log.d(TAG, "first frame rendered") }
            override fun onPlaybackStateChanged(state: Int) {
                Log.d(TAG, "state=" + when (state) { Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; else -> "ENDED" })
                _ended.value = state == Player.STATE_ENDED
                when (state) {
                    Player.STATE_ENDED -> setWantPlay(false)
                    Player.STATE_READY -> {
                        Log.d(TAG, "ready: player duration=${player.duration} project=$durationMs")
                        retries = 0
                        pendingSeekMs?.let { player.seekTo(it); pendingSeekMs = null }
                        // Reconcile: the user asked to play while the pipeline was still (re)building.
                        if (wantPlay && !player.playWhenReady) player.play()
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "player error", error)
                // Transient decoder / GL failures are common on busy devices: rebuild the pipeline a couple of times
                // before telling the user.
                val p = lastProject
                if (p != null && retries < MAX_RETRIES) {
                    retries++
                    lastPositionMs = runCatching { player.currentPosition }.getOrDefault(lastPositionMs)
                    signature = null
                    // can't swap the player from inside its own callback: do it right after
                    handler.post { recreatePlayer(); applyUpdate(p, lastPositionMs) }
                    return
                }
                setWantPlay(false)
                _error.value = "Preview failed: ${error.errorCodeName}"
            }
        }
    }

    init { player = newPlayer() }

    override fun attach(surface: SurfaceView) {
        Log.d(TAG, "attach surface valid=${surface.holder.surface.isValid}")
        surfaceView = surface
        player.setVideoSurfaceView(surface)
    }
    override fun detach(surface: SurfaceView) {
        if (surfaceView === surface) surfaceView = null
        player.clearVideoSurfaceView(surface)
    }

    override val positionMs: Long get() = player.currentPosition
    override val isPlaying: Boolean get() = player.isPlaying

    private fun signatureOf(p: Project): Int =
        p.copy(name = "", updatedAt = 0, exportCount = 0, texts = emptyList(), stickers = emptyList(), createdAt = 0, clips = p.clips.map { it.copy(vignette = 0f, bars = false) }).hashCode()

    /**
     * Re-targets the player at [p]. Rebuilds are debounced so dragging a slider doesn't tear the whole pipeline
     * down on every tick, which on a busy device left the preview stuck "buffering" and the play button dead.
     */
    override fun update(p: Project, positionMs: Long, shortSide: Int) {
        lastProject = p
        lastShortSide = shortSide
        lastPositionMs = positionMs
        if (signatureOf(p) == signature) return
        pendingUpdate?.let(handler::removeCallbacks)
        val first = signature == null
        val r = Runnable { pendingUpdate = null; lastProject?.let { applyUpdate(it, lastPositionMs) } }
        pendingUpdate = r
        if (first) r.run().also { pendingUpdate = null } else handler.postDelayed(r, REBUILD_DEBOUNCE_MS)
    }

    private fun applyUpdate(p: Project, positionMs: Long) {
        val sig = signatureOf(p)
        signature = sig
        _error.value = null
        durationMs = p.durationMs
        if (p.clips.isEmpty()) { setWantPlay(false); player.stop(); return }
        val (w, h) = CompositionBuilder.canvasSize(p.aspect, lastShortSide)
        runCatching {
            val comp = CompositionBuilder.build(p, RenderSpec(w, h, includeOverlays = false))
            Log.d(TAG, "setComposition ${w}x$h sequences=${comp.sequences.size} items=${comp.sequences.sumOf { it.editedMediaItems.size }}")
            player.setComposition(comp)
            pendingSeekMs = positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
            player.prepare()
        }.onFailure {
            Log.e(TAG, "preview setup failed", it)
            setWantPlay(false)
            _error.value = "Preview failed: ${it.message}"
        }
    }

    /** Make sure there is a prepared composition to play (the player is IDLE after errors, stop() and export). */
    private fun ensurePrepared() {
        pendingUpdate?.let { handler.removeCallbacks(it); pendingUpdate = null; lastProject?.let { p -> applyUpdate(p, lastPositionMs) } }
        if (player.playbackState == Player.STATE_IDLE) lastProject?.let { p -> if (p.clips.isNotEmpty()) applyUpdate(p, lastPositionMs) }
    }

    override fun play() {
        Log.d(TAG, "play() state=${player.playbackState} playWhenReady=${player.playWhenReady} pos=${player.currentPosition} dur=$durationMs")
        if (lastProject?.clips.isNullOrEmpty()) return
        // A fresh tap is a fresh attempt: earlier failures must not leave the button dead.
        if (player.playbackState == Player.STATE_IDLE) retries = 0
        _ended.value = false
        setWantPlay(true)
        ensurePrepared()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() { Log.d(TAG, "pause() pos=${player.currentPosition} dur=$durationMs state=${player.playbackState}"); setWantPlay(false); player.pause() }

    override fun seekTo(ms: Long) {
        val t = ms.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        lastPositionMs = t
        _ended.value = false
        if (player.playbackState == Player.STATE_IDLE) pendingSeekMs = t else player.seekTo(t)
    }

    override fun setScrubbing(on: Boolean) = runCatching { player.setScrubbingModeEnabled(on) }.let { }

    override fun suspend() {
        pendingUpdate?.let { handler.removeCallbacks(it); pendingUpdate = null }
        setWantPlay(false)
        signature = null
        runCatching { player.pause(); player.stop() }
    }

    override fun release() {
        pendingUpdate?.let { handler.removeCallbacks(it) }
        runCatching { player.release() }
    }
}
