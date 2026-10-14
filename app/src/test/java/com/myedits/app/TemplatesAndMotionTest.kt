package com.myedits.app

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myedits.app.audio.SoundLibrary
import com.myedits.app.data.*
import com.myedits.app.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@UnstableApi
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TemplatesAndMotionTest {
    private fun video(n: Int, ms: Long = 12_000) = List(n) { ProjectOps.makeVideoClip("/data/v$it.mp4", ms, 1080, 1920).copy(id = "v$it") }
    private fun base() = Project("p", "t", 0, 0)
    private val sound: (String, Long) -> AudioTrack? = { id, start ->
        SoundLibrary.byId(id)?.let { AudioTrack(ProjectOps.newId(), "/data/$id.wav", it.label, AudioKind.MUSIC, it.durationMs, startMs = start) }
    }

    // ---- catalogue sanity -----------------------------------------------------------------

    @Test fun catalogueIsRichAndConsistent() {
        assertTrue("at least 12 templates", Templates.all.size >= 12)
        assertEquals("ids are unique", Templates.all.size, Templates.all.map { it.id }.toSet().size)
        assertTrue(Templates.categories.size >= 4)
        Templates.all.forEach { t ->
            assertTrue("${t.id} slots", t.slotCount in 3..8)
            assertTrue("${t.id} duration ${t.totalMs}", t.totalMs in 4_000..24_000)
            assertTrue("${t.id} palette", t.palette.size >= 2)
            assertNotNull("${t.id} filter", FilterPresets.all.firstOrNull { it.id == t.filter })
            t.music?.let { assertNotNull("${t.id} music $it", SoundLibrary.byId(it)); assertTrue("music long enough", SoundLibrary.byId(it)!!.durationMs >= t.totalMs) }
            t.slots.forEach { s -> s.sfx?.let { assertNotNull("${t.id} sfx $it", SoundLibrary.byId(it)) }; assertTrue(s.ms >= 500) }
            assertTrue("${t.id} audio tracks fit", 1 + t.slots.count { it.sfx != null } <= CompositionBuilder.MAX_AUDIO_TRACKS)
        }
    }

    @Test fun lookupAndFiltering() {
        assertEquals("Beat Drop", Templates.byId("beat_drop")?.title)
        assertNull(Templates.byId("nope"))
        assertEquals(Templates.all.size, Templates.inCategory(null).size)
        TemplateCategory.entries.forEach { c -> assertTrue(Templates.inCategory(c).all { it.category == c }) }
        assertTrue(Templates.lookName(Templates.byId("cinema_trailer")!!).contains("cinema bars"))
    }

    // ---- applying a template ---------------------------------------------------------------

    @Test fun everyTemplateBuildsAValidTimeline() {
        Templates.all.forEach { t ->
            val p = Templates.apply(base(), t, video(t.slotCount), sound)
            assertEquals("${t.id} aspect", t.aspect, p.aspect)
            assertEquals("${t.id} clip count", t.slotCount, p.clips.size)
            assertEquals("${t.id} total length", t.totalMs, p.durationMs)
            assertEquals(t.id, p.template)
            p.clips.forEachIndexed { i, c ->
                assertEquals("${t.id} slot $i", t.slots[i].ms, c.durationMs)
                assertEquals(FitMode.FILL, c.fit)
                assertEquals(t.filter, c.filter)
                assertTrue(c.outMs <= c.sourceDurationMs && c.inMs >= 0)
            }
            assertEquals(Transition.NONE, p.clips.last().transition)
            assertEquals(t.slots.dropLast(1).map { it.transition }, p.clips.dropLast(1).map { it.transition })
            // captions are timed inside their slot
            val caps = t.slots.count { it.caption != null }
            assertEquals("${t.id} captions", caps, p.texts.size)
            p.texts.forEach { assertTrue(it.startMs >= 0 && it.endMs <= p.durationMs) }
            // music bed never outlasts the video
            val music = p.audios.filter { it.path.contains("music_") }
            assertEquals(if (t.music != null) 1 else 0, music.size)
            music.forEach { assertTrue(it.endMs <= p.durationMs) }
            assertEquals((if (t.music != null) 1 else 0) + t.slots.count { it.sfx != null }, p.audios.size)
            // the whole thing must produce a Media3 composition without throwing
            assertNotNull(CompositionBuilder.build(p, RenderSpec(540, 960, false)))
        }
    }

    @Test fun captionsAndSoundsFollowTheirSlot() {
        val t = Templates.byId("travel_diary")!!
        val p = Templates.apply(base(), t, video(5), sound)
        var at = 0L
        t.slots.forEachIndexed { i, s ->
            val text = p.texts.first { it.text == s.caption }
            assertTrue("caption $i starts inside its slot", text.startMs in at until at + s.ms)
            assertTrue(text.endMs <= at + s.ms)
            assertEquals(t.captionAnim, text.anim)
            at += s.ms
        }
        val sfx = Templates.byId("spin_cycle")!!
        val q = Templates.apply(base(), sfx, video(5), sound)
        assertEquals(listOf(0L, 1400L, 2800L, 4200L), q.audios.filter { it.kind == AudioKind.MUSIC && it.sourceDurationMs < 1000 }.map { it.startMs }.sorted())
    }

    @Test fun shortClipsPlayWholeAndPhotosTakeTheSlotLength() {
        val t = Templates.byId("beat_drop")!!
        val short = ProjectOps.makeVideoClip("/data/s.mp4", 700, 1080, 1920).copy(id = "s")
        val photo = ProjectOps.makeImageClip("/data/p.jpg", 1000, 1000)
        val p = Templates.apply(base(), t, listOf(short, photo, video(1).first()), sound)
        assertEquals(700L, p.clips[0].durationMs)
        assertTrue(p.clips[1].isImage); assertEquals(1200L, p.clips[1].durationMs)
        assertEquals(1200L, p.clips[2].durationMs)
    }

    @Test fun fewerOrMoreClipsThanSlotsStillWork() {
        val t = Templates.byId("glow_up")!!
        val one = Templates.apply(base(), t, video(1), sound)
        assertEquals(1, one.clips.size); assertEquals(Transition.NONE, one.clips[0].transition)
        assertTrue(one.audios.all { it.endMs <= one.durationMs })
        val many = Templates.apply(base(), t, video(5), sound)
        assertEquals(5, many.clips.size)
        assertEquals(t.slots[0].ms, many.clips[3].durationMs) // slots repeat in order
    }

    @Test fun missingSoundsAreSkippedNotFatal() {
        val p = Templates.apply(base(), Templates.byId("beat_drop")!!, video(6)) { _, _ -> null }
        assertTrue(p.audios.isEmpty()); assertEquals(6, p.clips.size)
    }

    @Test fun applyingReplacesEarlierLayers() {
        val first = Templates.apply(base(), Templates.byId("travel_diary")!!, video(5), sound)
        val again = Templates.apply(first, Templates.byId("noir")!!, video(3), sound)
        assertEquals(1, again.texts.size); assertEquals(AspectRatio.R4_5, again.aspect)
    }

    // ---- motion maths ---------------------------------------------------------------------

    @Test fun clipAnimationsStartAndEndWhereTheyShould() {
        assertTrue(MotionMath.clipAnim(ClipAnim.NONE, 0.5f).isIdentity)
        assertEquals(1f, MotionMath.clipAnim(ClipAnim.ZOOM_IN, 0f).scale, 1e-4f)
        assertTrue(MotionMath.clipAnim(ClipAnim.ZOOM_IN, 1f).scale > 1.2f)
        assertTrue(MotionMath.clipAnim(ClipAnim.ZOOM_OUT, 0f).scale > MotionMath.clipAnim(ClipAnim.ZOOM_OUT, 1f).scale)
        val l0 = MotionMath.clipAnim(ClipAnim.PAN_LEFT, 0f); val l1 = MotionMath.clipAnim(ClipAnim.PAN_LEFT, 1f)
        assertTrue(l0.tx > 0 && l1.tx < 0)
        // a panned frame must never reveal an edge: translation <= overscan
        listOf(ClipAnim.PAN_LEFT, ClipAnim.PAN_RIGHT).forEach { a -> for (i in 0..20) { val m = MotionMath.clipAnim(a, i / 20f); assertTrue(kotlin.math.abs(m.tx) <= m.scale - 1f + 1e-4f) } }
    }

    @Test fun transitionsAreIdentityAtWindowEdgesAndPeakAtTheCut() {
        Transition.entries.forEach { t ->
            assertTrue("$t edge", MotionMath.transition(t, -1f).isIdentity)
            assertTrue("$t edge", MotionMath.transition(t, 1f).isIdentity)
        }
        assertTrue(MotionMath.transition(Transition.ZOOM, 0f).scale > 1.5f)
        assertTrue(MotionMath.transition(Transition.SLIDE, -0.01f).tx < -1.5f && MotionMath.transition(Transition.SLIDE, 0.01f).tx > 1.5f)
        assertTrue(MotionMath.transition(Transition.SPIN, 0f).rotDeg != 0f)
        val (g, w) = MotionMath.transitionColor(Transition.FADE, 0f); assertEquals(0f, g, 1e-4f); assertEquals(0f, w, 1e-4f)
        val (g2, w2) = MotionMath.transitionColor(Transition.FLASH, 0f); assertEquals(1f, g2, 1e-4f); assertEquals(1f, w2, 1e-4f)
        assertEquals(1f to 0f, MotionMath.transitionColor(Transition.FADE, 1f))
    }

    @Test fun segmentsComeFromClipSpans() {
        val a = ProjectOps.makeVideoClip("/a", 4000, 10, 10).copy(id = "a", transition = Transition.ZOOM, transitionMs = 600, animation = ClipAnim.ZOOM_IN)
        val b = ProjectOps.makeVideoClip("/b", 4000, 10, 10).copy(id = "b", transition = Transition.FADE)
        val p = Project("p", "t", 0, 0, clips = listOf(a, b))
        val tr = MotionMath.transitionSegments(ProjectOps.spans(p))
        assertEquals(1, tr.size) // the last clip's transition has nothing to lead into
        assertEquals(4_000_000L, tr[0].boundaryUs); assertEquals(300_000L, tr[0].halfUs)
        assertEquals(1, MotionMath.animSegments(ProjectOps.spans(p)).size)
        val m = MotionMath.motionAt(4_000_000L, MotionMath.animSegments(ProjectOps.spans(p)), tr)
        assertTrue(m.scale > 1.5f)
        assertTrue(MotionMath.motionAt(2_000_000L, emptyList(), tr).isIdentity)
    }

    @Test fun layerAnimationsEaseInAndOut() {
        val s = 1000L; val e = 4000L
        assertEquals(0f, LayerAnimMath.pose(LayerAnim.FADE, s, s, e).alpha, 1e-3f)
        assertEquals(1f, LayerAnimMath.pose(LayerAnim.FADE, 2500, s, e).alpha, 1e-3f)
        assertEquals(0f, LayerAnimMath.pose(LayerAnim.FADE, e, s, e).alpha, 1e-3f)
        assertEquals(0.5f, LayerAnimMath.pose(LayerAnim.NONE, 2000, s, e, 0.5f).alpha, 1e-3f)
        assertEquals(0.4f, LayerAnimMath.pose(LayerAnim.FADE, 2500, s, e, 0.4f).alpha, 1e-3f)
        assertTrue(LayerAnimMath.pose(LayerAnim.POP, s, s, e).scale < 0.7f)
        assertEquals(1f, LayerAnimMath.pose(LayerAnim.POP, 2500, s, e).scale, 1e-3f)
        assertTrue(LayerAnimMath.pose(LayerAnim.SLIDE_UP, s, s, e).dy > 0f)
        assertEquals(0f, LayerAnimMath.pose(LayerAnim.SLIDE_UP, 2500, s, e).dy, 1e-4f)
        assertTrue(LayerAnimMath.pose(LayerAnim.SLIDE_LEFT, s, s, e).dx > 0f)
        // very short layers still finish their entrance before the exit
        val p = LayerAnimMath.pose(LayerAnim.FADE, 1100, 1000, 1200)
        assertTrue(p.alpha in 0f..1f)
    }

    // ---- ops ---------------------------------------------------------------------------------

    private fun three() = Project("p", "t", 0, 0, clips = video(3, 3000))

    @Test fun transitionsAreClampedAndNeverOnTheLastClip() {
        val p = three()
        val q = ProjectOps.setTransition(p, "v0", Transition.SLIDE, 99_999)
        assertEquals(Transition.SLIDE, q.clips[0].transition)
        assertEquals(ProjectOps.maxTransitionMs(q, "v0"), q.clips[0].transitionMs)
        assertEquals(1000L, q.clips[0].transitionMs)
        assertEquals(ProjectOps.MIN_TRANSITION_MS, ProjectOps.setTransition(p, "v0", Transition.FADE, 1).clips[0].transitionMs)
        val all = ProjectOps.setTransitionForAll(p, Transition.FLASH)
        assertEquals(listOf(Transition.FLASH, Transition.FLASH, Transition.NONE), all.clips.map { it.transition })
    }

    @Test fun insertClipAtSplitsOrSnaps() {
        val p = three()
        val still = ProjectOps.makeImageClip("/f.jpg", 10, 10, 2000).copy(id = "f")
        val mid = ProjectOps.insertClipAt(p, 1500, still)
        assertEquals(listOf("v0", "f", "v0"), mid.clips.map { if (it.id == "f") "f" else "v0" }.take(3))
        assertEquals(p.durationMs + 2000, mid.durationMs)
        assertEquals(1500L, mid.clips[0].durationMs)
        val atStart = ProjectOps.insertClipAt(p, 20, still)
        assertEquals("f", atStart.clips[0].id)
        val atEnd = ProjectOps.insertClipAt(p, p.durationMs, still)
        assertEquals("f", atEnd.clips.last().id)
        val boundary = ProjectOps.insertClipAt(p, 3000, still)
        assertEquals(listOf("v0", "f", "v1", "v2"), boundary.clips.map { it.id })
        assertEquals(listOf(still), ProjectOps.insertClipAt(Project("e", "e", 0, 0), 0, still).clips)
    }

    @Test fun captionsFromScriptSpreadEvenly() {
        val p = Project("p", "t", 0, 0, clips = video(1, 9000))
        val q = ProjectOps.captionsFromScript(p, "One\n\n  Two \nThree\n")
        assertEquals(listOf("One", "Two", "Three"), q.texts.map { it.text })
        assertEquals(listOf(0L, 3000L, 6000L), q.texts.map { it.startMs })
        assertEquals(9000L, q.texts.last().endMs)
        assertEquals(q.texts.size, q.texts.map { it.id }.toSet().size)
        assertSame(p, ProjectOps.captionsFromScript(p, "   \n "))
    }

    // ---- composition ---------------------------------------------------------------------------

    @Test fun effectsReachTheComposition() {
        val a = ProjectOps.makeVideoClip("/a.mp4", 4000, 1080, 1920).copy(id = "a", transition = Transition.ZOOM, animation = ClipAnim.ZOOM_IN, vignette = 0.5f, bars = true, blur = 0.4f, zoom = 1.5f, panX = 0.5f)
        val b = ProjectOps.makeVideoClip("/b.mp4", 4000, 1080, 1920).copy(id = "b", transition = Transition.FLASH)
        val c = ProjectOps.makeVideoClip("/c.mp4", 4000, 1080, 1920).copy(id = "c")
        val p = Project("p", "t", 0, 0, clips = listOf(a, b, c))
        val plain = CompositionBuilder.build(p, RenderSpec(540, 960, false)).effects.videoEffects
        assertTrue(plain.any { it is MotionEffect }); assertTrue(plain.any { it is FadeEffect })
        assertTrue(plain.none { it is androidx.media3.effect.OverlayEffect }) // preview draws vignette/bars itself
        val full = CompositionBuilder.build(p, RenderSpec(540, 960, true)).effects.videoEffects
        assertTrue(full.any { it is androidx.media3.effect.OverlayEffect })
        val item = CompositionBuilder.clipItem(a, RenderSpec(540, 960, false)).effects.videoEffects
        assertTrue(item.any { it is androidx.media3.effect.GaussianBlur })
        assertTrue(item.any { it is androidx.media3.effect.MatrixTransformation })
        assertEquals(2, CompositionBuilder.overlays(p, 540, 960).size) // vignette + bars of clip a
    }

    @Test fun pitchAddsAnAudioProcessor() {
        val a = AudioTrack("t", "/a.wav", "x", AudioKind.VOICE, 3000)
        val clip = ProjectOps.makeVideoClip("/a.mp4", 4000, 10, 10)
        val plain = CompositionBuilder.build(Project("p", "t", 0, 0, clips = listOf(clip), audios = listOf(a)), RenderSpec(540, 960, false))
        val pitched = CompositionBuilder.build(Project("p", "t", 0, 0, clips = listOf(clip), audios = listOf(a.copy(pitch = 1.5f))), RenderSpec(540, 960, false))
        assertEquals(plain.sequences[1].editedMediaItems[0].effects.audioProcessors.size + 1, pitched.sequences[1].editedMediaItems[0].effects.audioProcessors.size)
    }
}
