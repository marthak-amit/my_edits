package com.myedits.app.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.myedits.app.data.*
import java.io.File
import kotlin.math.roundToInt

/** Output size / frame rate request for a build. */
data class RenderSpec(
    val width: Int,
    val height: Int,
    /** Burn text and stickers into the frames (export). Preview draws them with Compose instead. */
    val includeOverlays: Boolean,
    /** 0 = keep source frame rate. */
    val fps: Int = 0,
)

/**
 * Translates the editable [Project] model into a Media3 [Composition]. The very same
 * composition drives both the live preview and the export, so they cannot drift apart.
 */
@UnstableApi
object CompositionBuilder {

    /** Canvas dimensions where [shortSide] is the smaller edge (so "1080p" is 1080x1920 portrait). */
    fun canvasSize(aspect: AspectRatio, shortSide: Int): Pair<Int, Int> {
        val r = aspect.ratio
        val short = shortSide.toFloat()
        val w = if (r <= 1f) short else short * r
        val h = if (r <= 1f) short / r else short
        return even(w.roundToInt()) to even(h.roundToInt())
    }

    private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1

    fun build(p: Project, spec: RenderSpec): Composition {
        require(p.clips.isNotEmpty()) { "Project has no clips" }
        val totalMs = p.durationMs

        // --- main video track -----------------------------------------------------------
        val items = p.clips.map { clipItem(it, spec) }
        val video = EditedMediaItemSequence.Builder(items)
            .experimentalSetForceAudioTrack(true)
            .build()

        // --- audio tracks, one sequence each so they may overlap freely --------------------
        val sequences = mutableListOf(video)
        p.audios.filter { it.startMs < totalMs }.take(MAX_AUDIO_TRACKS).forEach { sequences += audioSequence(it, totalMs) }

        // --- composition level effects: fades first, then burned-in overlays, then fps ---------
        val videoEffects = mutableListOf<Effect>()
        val fades = fadeSegments(p)
        if (fades.any { it.fadeInUs > 0 || it.fadeOutUs > 0 }) videoEffects += FadeEffect(fades)
        if (spec.includeOverlays) {
            val overlays = overlays(p, spec.width, spec.height)
            if (overlays.isNotEmpty()) videoEffects += OverlayEffect(overlays)
        }
        if (spec.fps > 0) videoEffects += FrameDropEffect.createDefaultFrameDropEffect(spec.fps.toFloat())

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .experimentalSetForceAudioTrack(true)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    const val MAX_AUDIO_TRACKS = 8

    fun fadeSegments(p: Project): List<FadeSegment> = ProjectOps.spans(p).map {
        FadeSegment(it.startMs * 1000, it.endMs * 1000, it.clip.fadeInMs * 1000, it.clip.fadeOutMs * 1000)
    }

    private fun uri(path: String): Uri = Uri.fromFile(File(path))

    fun imageMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    fun clipItem(c: Clip, spec: RenderSpec): EditedMediaItem {
        val mediaItem = MediaItem.Builder().setUri(uri(c.path)).apply {
            if (c.isImage) {
                // Without an image MIME type Media3 treats the file as video and no extractor can read it.
                setMimeType(imageMimeType(c.path))
                setImageDurationMs(c.durationMs)
            } else {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.inMs)
                        .setEndPositionMs(c.outMs)
                        .build(),
                )
            }
        }.build()

        val videoEffects = mutableListOf<Effect>()
        // 1. orientation
        if (c.rotation != 0 || c.flipH || c.flipV) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-c.rotation.toFloat()) // Media3 rotates counter-clockwise
                .setScale(if (c.flipH) -1f else 1f, if (c.flipV) -1f else 1f)
                .build()
        }
        // 2. colour
        val m = ColorMath.clipMatrix(c.filter, c.filterIntensity, c.adjust)
        if (!ColorMath.isIdentity(m)) videoEffects += RgbMatrix { _, _ -> m }

        val audioProcessors = mutableListOf<AudioProcessor>()
        // 3. speed (video timestamps + matching audio resampling). The classic SpeedChangeEffect/Sonic pair is used
        //    because the experimental speed-provider variant dropped the tail of the last item's video on device.
        if (!c.isImage && c.speed != 1f) {
            videoEffects += SpeedChangeEffect(c.speed)
            audioProcessors += SonicAudioProcessor().apply { setSpeed(c.speed) }
        }
        // 4. fit/fill into the canvas
        videoEffects += Presentation.createForWidthAndHeight(
            spec.width, spec.height,
            if (c.fit == FitMode.FIT) Presentation.LAYOUT_SCALE_TO_FIT else Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
        )
        // 5. user zoom, about the frame centre
        if (c.zoom != 1f) {
            val z = c.zoom
            videoEffects += MatrixTransformation { _ -> Matrix().apply { postScale(z, z) } }
        }
        // audio gain after speed so fades are measured in output time
        audioProcessors += GainAudioProcessor(c.volume, c.fadeInMs, c.fadeOutMs, c.durationMs)

        return EditedMediaItem.Builder(mediaItem)
            .apply {
                // CompositionPlayer needs every item's duration up front: for photos it is the display time,
                // for videos the length of the *source* file (the clipping configuration trims it).
                if (c.isImage) setDurationUs(c.durationMs * 1000).setFrameRate(30)
                else setDurationUs(c.sourceDurationMs * 1000)
            }
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    private fun audioSequence(a: AudioTrack, totalMs: Long): EditedMediaItemSequence {
        val room = (totalMs - a.startMs).coerceAtLeast(1L)
        val outMs = minOf(a.outMs, a.inMs + room)
        val durationMs = outMs - a.inMs
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(uri(a.path)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.inMs).setEndPositionMs(outMs).build(),
            ).build(),
        )
            .setRemoveVideo(true)
            .setDurationUs(a.sourceDurationMs * 1000)
            .setEffects(
                Effects(
                    listOf(GainAudioProcessor(a.volume, a.fadeInMs, minOf(a.fadeOutMs, durationMs), durationMs)),
                    emptyList(),
                ),
            )
            .build()
        return EditedMediaItemSequence.Builder().apply {
            if (a.startMs > 0) addGap(a.startMs * 1000)
            addItem(item)
            // Required by Media3 whenever a sequence starts with a gap (audio that begins after 0:00).
            experimentalSetForceAudioTrack(true)
        }.build()
    }

    // ---- burned-in overlays ---------------------------------------------------------------

    /** A bitmap overlay that is only visible during [startUs, endUs). */
    private class TimedOverlay(
        private val bitmap: Bitmap,
        private val startUs: Long,
        private val endUs: Long,
        private val visible: OverlaySettings,
        private val hidden: OverlaySettings,
    ) : BitmapOverlay() {
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            if (presentationTimeUs in startUs until endUs) visible else hidden
    }

    fun overlays(p: Project, w: Int, h: Int): List<TextureOverlay> {
        val out = mutableListOf<TextureOverlay>()
        p.stickers.forEach { s ->
            val bmp = LayerRenderer.renderSticker(s, w, h)
            out += timed(bmp, s.startMs, s.endMs, s.x, s.y, s.rotation)
        }
        p.texts.forEach { t ->
            val bmp = LayerRenderer.renderText(t, w, h)
            out += timed(bmp, t.startMs, t.endMs, t.x, t.y, t.rotation)
        }
        return out
    }

    private fun timed(bmp: Bitmap, startMs: Long, endMs: Long, x: Float, y: Float, rotation: Float): TextureOverlay {
        fun settings(alpha: Float): OverlaySettings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(x * 2f - 1f, 1f - y * 2f)
            .setRotationDegrees(-rotation)
            .setAlphaScale(alpha)
            .build()
        return TimedOverlay(bmp, startMs * 1000, endMs * 1000, settings(1f), settings(0f))
    }
}
