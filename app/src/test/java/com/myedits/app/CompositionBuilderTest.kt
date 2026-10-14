package com.myedits.app

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myedits.app.data.*
import com.myedits.app.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CompositionBuilderTest {
    private val spec = RenderSpec(540, 960, includeOverlays = false)

    private fun clip(id: String, ms: Long = 4000) = ProjectOps.makeVideoClip("/data/$id.mp4", ms, 1080, 1920).copy(id = id)
    private fun project(vararg c: Clip) = Project("p", "t", 0, 0, clips = c.toList())

    @Test fun canvasSizesMatchAspectAndAreEven() {
        assertEquals(1080 to 1920, CompositionBuilder.canvasSize(AspectRatio.R9_16, 1080))
        assertEquals(1920 to 1080, CompositionBuilder.canvasSize(AspectRatio.R16_9, 1080))
        assertEquals(1080 to 1350, CompositionBuilder.canvasSize(AspectRatio.R4_5, 1080))
        assertEquals(720 to 720, CompositionBuilder.canvasSize(AspectRatio.R1_1, 720))
        assertEquals(540 to 960, CompositionBuilder.canvasSize(AspectRatio.R9_16, 540))
        AspectRatio.entries.forEach { a -> listOf(540, 720, 1080, 2160).forEach { s ->
            val (w, h) = CompositionBuilder.canvasSize(a, s); assertTrue("$a $s", w % 2 == 0 && h % 2 == 0)
        } }
    }

    @Test fun oneVideoSequenceHoldsEveryClipInOrder() {
        val c = CompositionBuilder.build(project(clip("a"), clip("b"), clip("c")), spec)
        assertEquals(1, c.sequences.size)
        assertEquals(3, c.sequences[0].editedMediaItems.size)
        assertEquals(listOf("file:///data/a.mp4", "file:///data/b.mp4", "file:///data/c.mp4"), c.sequences[0].editedMediaItems.map { it.mediaItem.localConfiguration!!.uri.toString() })
    }

    @Test fun clipTrimBecomesClippingConfiguration() {
        val item = CompositionBuilder.clipItem(clip("a", 8000).copy(inMs = 1500, outMs = 6500), spec)
        assertEquals(1500L, item.mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(6500L, item.mediaItem.clippingConfiguration.endPositionMs)
    }

    @Test fun presentationAlwaysFitsToCanvas() {
        val e = CompositionBuilder.clipItem(clip("a"), spec).effects.videoEffects
        assertTrue(e.any { it is Presentation })
        val fill = CompositionBuilder.clipItem(clip("a").copy(fit = FitMode.FILL), spec).effects.videoEffects
        assertTrue(fill.any { it is Presentation })
    }

    @Test fun plainClipHasNoRedundantEffects() {
        val e = CompositionBuilder.clipItem(clip("a"), spec).effects
        assertEquals(listOf(Presentation::class.java), e.videoEffects.map { it.javaClass })
        // volume 1 and no fades: the gain processor is present but reports itself inactive
        assertTrue(e.audioProcessors.none { it.isActive })
    }

    @Test fun orientationColourSpeedAndZoomAddTheirEffects() {
        val c = clip("a").copy(rotation = 90, flipH = true, filter = "vivid", speed = 2f, zoom = 1.5f)
        val types = CompositionBuilder.clipItem(c, spec).effects.videoEffects.map { it.javaClass.simpleName }
        assertTrue(types.toString(), types.contains("ScaleAndRotateTransformation"))
        assertTrue(types.toString(), types.any { it.contains("Lambda") || it.contains("RgbMatrix") || it.contains("\$\$") })
        assertTrue(types.toString(), types.contains("SpeedChangeEffect"))
        assertTrue(types.toString(), types.contains("Presentation"))
        // Presentation must come after rotation/colour/speed and before the zoom
        assertTrue(types.indexOf("ScaleAndRotateTransformation") < types.indexOf("Presentation"))
        assertTrue(types.indexOf("SpeedChangeEffect") < types.indexOf("Presentation"))
        assertTrue(types.indexOf("Presentation") < types.lastIndex)
    }

    @Test fun speedAddsAudioProcessorsForVideoButNotImages() {
        val v = CompositionBuilder.clipItem(clip("a").copy(speed = 2f), spec).effects
        assertEquals(2, v.audioProcessors.size) // speed + gain
        val img = CompositionBuilder.clipItem(ProjectOps.makeImageClip("/x.jpg", 100, 100).copy(speed = 2f), spec).effects
        assertEquals(1, img.audioProcessors.size) // only gain
        assertEquals(1500L, CompositionBuilder.clipItem(ProjectOps.makeImageClip("/x.jpg", 100, 100).copy(speed = 2f), spec).durationUs / 1000)
    }

    @Test fun everyItemDeclaresADurationBecauseCompositionPlayerRequiresIt() {
        // Regression: on a real device setComposition() threw IllegalStateException(TIME_UNSET) for videos without one.
        val a = AudioTrack("m", "/data/m.m4a", "Song", AudioKind.MUSIC, 7000, startMs = 500)
        val p = project(clip("a", 5000).copy(inMs = 1000, outMs = 3000), ProjectOps.makeImageClip("/x.jpg", 10, 10, 2000)).copy(audios = listOf(a))
        val c = CompositionBuilder.build(p, spec)
        c.sequences.forEach { seq -> seq.editedMediaItems.forEach { assertNotEquals("duration unset for ${it.mediaItem.localConfiguration?.uri}", C.TIME_UNSET, it.durationUs) } }
        assertEquals("video: full source length", 5_000_000L, c.sequences[0].editedMediaItems[0].durationUs)
        assertEquals("image: display time", 2_000_000L, c.sequences[0].editedMediaItems[1].durationUs)
        assertEquals("audio: full source length", 7_000_000L, c.sequences[1].editedMediaItems[1].durationUs)
    }

    @Test fun imagesCarryAnImageMimeTypeAndDurationOnTheMediaItem() {
        // Regression: on a real device a JPEG without a MIME type was routed to ExoPlayer and export failed with
        // UnrecognizedInputFormatException.
        listOf("a.jpg" to "image/jpeg", "b.PNG" to "image/png", "c.webp" to "image/webp", "d.heic" to "image/heic", "e.unknown" to "image/jpeg").forEach { (name, mime) ->
            val item = CompositionBuilder.clipItem(ProjectOps.makeImageClip("/data/$name", 10, 10, 2500), spec)
            assertEquals(name, mime, item.mediaItem.localConfiguration!!.mimeType)
            assertEquals(name, 2500L, item.mediaItem.localConfiguration!!.imageDurationMs)
        }
        val video = CompositionBuilder.clipItem(clip("v"), spec)
        assertNull(video.mediaItem.localConfiguration!!.mimeType)
    }

    @Test fun imagesGetDurationAndFrameRate() {
        val item = CompositionBuilder.clipItem(ProjectOps.makeImageClip("/x.jpg", 100, 100, 2500), spec)
        assertEquals(2_500_000L, item.durationUs); assertEquals(30, item.frameRate)
    }

    @Test fun volumeAndFadesActivateTheGainProcessor() {
        val e = CompositionBuilder.clipItem(clip("a").copy(volume = 0.5f), spec).effects
        assertTrue(e.audioProcessors.any { it.isActive.not() }.not().not())
        val faded = CompositionBuilder.clipItem(clip("a").copy(fadeInMs = 500), spec).effects
        assertEquals(1, faded.audioProcessors.size)
    }

    @Test fun audioTracksBecomeOwnSequencesAndAreTruncatedToTheProject() {
        val a = AudioTrack("m", "/data/m.m4a", "Song", AudioKind.MUSIC, 60_000, startMs = 1000)
        val p = project(clip("a", 5000)).copy(audios = listOf(a, a.copy(id = "n", startMs = 9000)))
        val c = CompositionBuilder.build(p, spec)
        assertEquals("track starting after the end is dropped", 2, c.sequences.size)
        val audio = c.sequences[1].editedMediaItems.last() // first entry is the start-offset gap
        assertTrue(audio.removeVideo)
        assertEquals(4000L, audio.mediaItem.clippingConfiguration.endPositionMs - audio.mediaItem.clippingConfiguration.startPositionMs)
    }

    @Test fun audioStartOffsetIsAGapAheadOfTheItem() {
        val a = AudioTrack("m", "/data/m.m4a", "Song", AudioKind.MUSIC, 2000, startMs = 1500)
        val seq = CompositionBuilder.build(project(clip("a", 5000)).copy(audios = listOf(a)), spec).sequences[1]
        assertEquals(2, seq.editedMediaItems.size)
        assertEquals(1_500_000L, seq.editedMediaItems[0].durationUs)
    }

    @Test fun fadesAddACompositionLevelEffectOnlyWhenNeeded() {
        val none = CompositionBuilder.build(project(clip("a")), spec).effects.videoEffects
        assertTrue(none.none { it is FadeEffect })
        val some = CompositionBuilder.build(project(clip("a").copy(fadeInMs = 400)), spec).effects.videoEffects
        assertTrue(some.any { it is FadeEffect })
    }

    @Test fun fadeSegmentsUseAbsoluteTimelineTimes() {
        val segs = CompositionBuilder.fadeSegments(project(clip("a", 2000).copy(fadeOutMs = 500), clip("b", 3000).copy(fadeInMs = 700)))
        assertEquals(FadeSegment(0, 2_000_000, 0, 500_000), segs[0])
        assertEquals(FadeSegment(2_000_000, 5_000_000, 700_000, 0), segs[1])
    }

    @Test fun overlaysAreOnlyBurnedInWhenRequested() {
        var p = project(clip("a", 6000))
        p = ProjectOps.addText(p, TextLayer("t", "Hi", 1000, 2000))
        p = ProjectOps.addSticker(p, ProjectOps.defaultSticker(p, 0, "🔥"))
        assertTrue(CompositionBuilder.build(p, spec).effects.videoEffects.none { it is OverlayEffect })
        val burned = CompositionBuilder.build(p, spec.copy(includeOverlays = true)).effects.videoEffects
        assertEquals(1, burned.count { it is OverlayEffect })
        assertEquals(2, CompositionBuilder.overlays(p, 540, 960).size)
    }

    @Test fun overlayIsOnlyVisibleDuringItsTimeRange() {
        val p = ProjectOps.addText(project(clip("a", 6000)), TextLayer("t", "Hi", 1000, 2000, x = 0.25f, y = 0.75f, rotation = 30f))
        val o = CompositionBuilder.overlays(p, 540, 960).single()
        assertEquals(0f, o.getOverlaySettings(500_000).alphaScale, 0f)
        assertEquals(1f, o.getOverlaySettings(1_000_000).alphaScale, 0f)
        assertEquals(1f, o.getOverlaySettings(2_999_999).alphaScale, 0f)
        assertEquals(0f, o.getOverlaySettings(3_000_000).alphaScale, 0f)
        val s = o.getOverlaySettings(1_500_000)
        assertEquals(-0.5f, s.backgroundFrameAnchor.first, 1e-5f)   // x 0.25 -> NDC -0.5
        assertEquals(-0.5f, s.backgroundFrameAnchor.second, 1e-5f)  // y 0.75 -> NDC -0.5 (y axis points up)
        assertEquals(-30f, s.rotationDegrees, 1e-5f)
    }

    @Test fun emptyProjectCannotBeBuilt() {
        try { CompositionBuilder.build(project(), spec); fail("expected failure") } catch (_: IllegalArgumentException) {}
    }

    @Test fun framerateRequestAddsFrameDrop() {
        val e = CompositionBuilder.build(project(clip("a")), spec.copy(fps = 24)).effects.videoEffects
        assertTrue(e.any { it is FrameDropEffect })
    }
}

@UnstableApi
@RunWith(AndroidJUnit4::class)
class GainProcessorTest {
    private fun pcm(vararg v: Int): ByteBuffer = ByteBuffer.allocate(v.size * 2).order(ByteOrder.nativeOrder()).also { b -> v.forEach { b.putShort(it.toShort()) }; b.flip() }

    private fun run(p: GainAudioProcessor, input: ByteBuffer, rate: Int = 1000, channels: Int = 1): ShortArray {
        p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); p.flush()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    @Test fun constantVolumeScalesSamples() {
        val out = run(GainAudioProcessor(0.5f, 0, 0, 1000), pcm(1000, -1000, 20000))
        assertArrayEquals(shortArrayOf(500, -500, 10000), out)
    }

    @Test fun boostClipsInsteadOfWrapping() {
        val out = run(GainAudioProcessor(2f, 0, 0, 1000), pcm(30000, -30000))
        assertArrayEquals(shortArrayOf(32767, -32768), out)
    }

    @Test fun fadeInRampsFromSilence() {
        // 1000 Hz, 1 sample = 1 ms; 4 ms fade-in over constant 1000
        val out = run(GainAudioProcessor(1f, 4, 0, 100), pcm(1000, 1000, 1000, 1000, 1000))
        assertEquals(0, out[0].toInt()); assertEquals(250, out[1].toInt()); assertEquals(500, out[2].toInt()); assertEquals(750, out[3].toInt()); assertEquals(1000, out[4].toInt())
    }

    @Test fun fadeOutRampsToSilenceAtTheEnd() {
        val out = run(GainAudioProcessor(1f, 0, 4, 8), pcm(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000))
        assertEquals(1000, out[3].toInt()); assertEquals(1000, out[4].toInt()); assertEquals(750, out[5].toInt()); assertEquals(500, out[6].toInt()); assertEquals(250, out[7].toInt())
    }

    @Test fun stereoChannelsShareTheSameGain() {
        val out = run(GainAudioProcessor(0.5f, 0, 0, 1000), pcm(1000, 2000, 3000, 4000), channels = 2)
        assertArrayEquals(shortArrayOf(500, 1000, 1500, 2000), out)
    }

    @Test fun partialTrailingFrameIsStillConsumed() {
        val p = GainAudioProcessor(0.5f, 0, 0, 1000)
        p.configure(AudioProcessor.AudioFormat(1000, 2, C.ENCODING_PCM_16BIT)); p.flush()
        val input = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder()).apply { putShort(100); putShort(200); putShort(300); flip() } // 1.5 frames
        p.queueInput(input)
        assertFalse("input fully consumed", input.hasRemaining())
        assertEquals(6, p.output.remaining())
    }

    @Test fun neutralProcessorIsInactiveAndOtherEncodingsAreRejected() {
        val p = GainAudioProcessor(1f, 0, 0, 1000)
        p.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        assertFalse(p.isActive)
        try { GainAudioProcessor(0.5f, 0, 0, 10).configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)); fail() } catch (_: AudioProcessor.UnhandledAudioFormatException) {}
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LayerRendererTest {
    private fun nonTransparent(b: android.graphics.Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) if (android.graphics.Color.alpha(b.getPixel(x, y)) > 20) n++
        return n
    }

    @Test fun textRendersVisiblePixelsAndGrowsWithScale() {
        val t = TextLayer("t", "Hello world", 0, 1000, shadow = false)
        val small = LayerRenderer.renderText(t.copy(scale = 1f), 1080, 1920)
        val big = LayerRenderer.renderText(t.copy(scale = 1.5f), 1080, 1920) // 2x would hit the wrap width
        assertTrue(nonTransparent(small) > 50)
        assertTrue(big.width > small.width * 1.4f && big.height > small.height * 1.4f)
    }

    @Test fun longTextWrapsInsideTheCanvas() {
        val t = TextLayer("t", "word ".repeat(60), 0, 1000)
        val b = LayerRenderer.renderText(t, 540, 960)
        assertTrue("width ${b.width}", b.width <= 540)
        assertTrue(b.height > LayerRenderer.textSizePx(960, 1f) * 2)
    }

    @Test fun backgroundAddsPaddingAndColour() {
        val plain = LayerRenderer.renderText(TextLayer("t", "Hi", 0, 1, bg = TextBg.NONE), 1080, 1920)
        val boxed = LayerRenderer.renderText(TextLayer("t", "Hi", 0, 1, bg = TextBg.ROUNDED, bgColor = 0xFF112233), 1080, 1920)
        assertTrue(boxed.width > plain.width && boxed.height > plain.height)
        assertEquals(0xFF112233.toInt(), boxed.getPixel(boxed.width / 2, 8 + (LayerRenderer.textSizePx(1920, 1f) * 0.35f).toInt()).let { it })
    }

    @Test fun emptyTextStillProducesAUsableBitmap() {
        val b = LayerRenderer.renderText(TextLayer("t", "", 0, 1), 1080, 1920)
        assertTrue(b.width > 0 && b.height > 0)
    }

    @Test fun emojiStickerScalesWithCanvasWidth() {
        val s = StickerLayer("s", StickerKind.EMOJI, emoji = "🔥", startMs = 0, durationMs = 1, scale = 0.25f)
        assertEquals(270, LayerRenderer.renderSticker(s, 1080, 1920).width)
        assertEquals(135, LayerRenderer.renderSticker(s, 540, 960).width)
    }

    @Test fun missingImageStickerFallsBackToAPlaceholder() {
        val s = StickerLayer("s", StickerKind.IMAGE, path = "/does/not/exist.jpg", startMs = 0, durationMs = 1, scale = 0.3f)
        val b = LayerRenderer.renderSticker(s, 1000, 1000)
        assertEquals(300, b.width)
    }

    @Test fun imageStickerKeepsAspectRatio() {
        val f = java.io.File.createTempFile("img", ".jpg")
        android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, f.outputStream())
        val b = LayerRenderer.renderSticker(StickerLayer("s", StickerKind.IMAGE, path = f.path, startMs = 0, durationMs = 1, scale = 0.5f), 1000, 1000)
        assertEquals(500, b.width); assertEquals(250, b.height)
        f.delete()
    }
}
