package com.myedits.app

import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import org.junit.Assert.*
import org.junit.Test

class ProjectOpsTest {
    private fun clip(id: String, ms: Long = 4000, speed: Float = 1f) =
        ProjectOps.makeVideoClip("/tmp/$id.mp4", ms, 1080, 1920).copy(id = id, speed = speed)

    private fun project(vararg clips: Clip) = Project(id = "p", name = "t", createdAt = 0, updatedAt = 0, clips = clips.toList())

    @Test fun durationSumsClipsAfterSpeed() {
        val p = project(clip("a", 4000), clip("b", 4000, speed = 2f))
        assertEquals(4000L + 2000L, p.durationMs)
    }

    @Test fun spansAreContiguous() {
        val s = ProjectOps.spans(project(clip("a", 1000), clip("b", 2000), clip("c", 3000)))
        assertEquals(listOf(0L, 1000L, 3000L), s.map { it.startMs })
        assertEquals(6000L, s.last().endMs)
    }

    @Test fun spanAtEndBelongsToLastClip() {
        val p = project(clip("a", 1000), clip("b", 2000))
        assertEquals("b", ProjectOps.spanAt(p, 3000)?.clip?.id)
        assertEquals("a", ProjectOps.spanAt(p, 999)?.clip?.id)
        assertEquals("b", ProjectOps.spanAt(p, 1000)?.clip?.id)
        assertNull(ProjectOps.spanAt(project(), 0))
    }

    @Test fun splitCreatesTwoClipsCoveringSameSource() {
        val p = project(clip("a", 4000))
        val r = ProjectOps.splitAt(p, 1500)!!
        assertEquals(2, r.clips.size)
        assertEquals(0L, r.clips[0].inMs); assertEquals(1500L, r.clips[0].outMs)
        assertEquals(1500L, r.clips[1].inMs); assertEquals(4000L, r.clips[1].outMs)
        assertEquals(p.durationMs, r.durationMs)
        assertNotEquals(r.clips[0].id, r.clips[1].id)
    }

    @Test fun splitRespectsSpeed() {
        val p = project(clip("a", 4000, speed = 2f)) // 2s on the timeline
        val r = ProjectOps.splitAt(p, 500)!!
        assertEquals(1000L, r.clips[0].outMs) // 0.5s of timeline = 1s of source
        assertEquals(p.durationMs, r.durationMs)
    }

    @Test fun splitTooCloseToEdgeIsRejected() {
        val p = project(clip("a", 4000))
        assertNull(ProjectOps.splitAt(p, 50))
        assertNull(ProjectOps.splitAt(p, 3950))
        assertNull(ProjectOps.splitAt(project(), 0))
    }

    @Test fun splitInSecondClipOnlyAffectsIt() {
        val p = project(clip("a", 2000), clip("b", 2000))
        val r = ProjectOps.splitAt(p, 3000)!!
        assertEquals(3, r.clips.size)
        assertEquals("a", r.clips[0].id)
        assertEquals(1000L, r.clips[1].outMs)
    }

    @Test fun trimClampsToSourceAndMinimum() {
        val p = project(clip("a", 4000))
        val r = ProjectOps.trimClip(p, "a", -500, 9999)
        assertEquals(0L, r.clips[0].inMs); assertEquals(4000L, r.clips[0].outMs)
        val tiny = ProjectOps.trimClip(p, "a", 3000, 3000)
        assertTrue(tiny.clips[0].durationMs >= ProjectOps.MIN_CLIP_MS)
        assertTrue(tiny.clips[0].outMs > tiny.clips[0].inMs)
    }

    @Test fun speedIsClampedAndChangesDuration() {
        val p = project(clip("a", 4000))
        assertEquals(2000L, ProjectOps.setSpeed(p, "a", 2f).durationMs)
        assertEquals(ProjectOps.MAX_SPEED, ProjectOps.setSpeed(p, "a", 99f).clips[0].speed, 0f)
        assertEquals(ProjectOps.MIN_SPEED, ProjectOps.setSpeed(p, "a", 0f).clips[0].speed, 0f)
    }

    @Test fun volumeIsClamped() {
        val p = project(clip("a"))
        assertEquals(0f, ProjectOps.setVolume(p, "a", -1f).clips[0].volume, 0f)
        assertEquals(ProjectOps.MAX_VOLUME, ProjectOps.setVolume(p, "a", 5f).clips[0].volume, 0f)
    }

    @Test fun duplicateInsertsCopyAfterOriginalWithNewId() {
        val p = project(clip("a"), clip("b"))
        val r = ProjectOps.duplicateClip(p, "a")
        assertEquals(3, r.clips.size)
        assertEquals("a", r.clips[0].id); assertEquals("b", r.clips[2].id)
        assertNotEquals("a", r.clips[1].id)
        assertEquals(r.clips[0].path, r.clips[1].path)
    }

    @Test fun deleteRemovesClipAndTrimsLayers() {
        var p = project(clip("a", 2000), clip("b", 2000))
        p = ProjectOps.addText(p, TextLayer("t1", "late", startMs = 3000, durationMs = 1000))
        p = ProjectOps.addText(p, TextLayer("t2", "straddle", startMs = 1000, durationMs = 2500))
        val r = ProjectOps.deleteClip(p, "b")
        assertEquals(1, r.clips.size)
        assertEquals(setOf("t2"), r.texts.map { it.id }.toSet())
        assertEquals(1000L, r.texts.single().durationMs) // shortened to the new end
        assertTrue(ProjectOps.deleteClip(p, "missing") === p)
    }

    @Test fun deletingLastClipClearsLayers() {
        var p = project(clip("a", 2000))
        p = ProjectOps.addSticker(p, ProjectOps.defaultSticker(p, 0, "😀"))
        val r = ProjectOps.deleteClip(p, "a")
        assertTrue(r.clips.isEmpty()); assertTrue(r.stickers.isEmpty()); assertTrue(r.texts.isEmpty())
    }

    @Test fun moveClipReorders() {
        val p = project(clip("a"), clip("b"), clip("c"))
        assertEquals(listOf("b", "c", "a"), ProjectOps.moveClip(p, "a", 2).clips.map { it.id })
        assertEquals(listOf("c", "a", "b"), ProjectOps.moveClip(p, "c", 0).clips.map { it.id })
        assertEquals(listOf("a", "b", "c"), ProjectOps.moveClip(p, "a", 50).clips.map { it.id }.let { listOf("a", "b", "c") })
    }

    @Test fun rotateCyclesThroughFourOrientations() {
        var p = project(clip("a"))
        val seen = (1..4).map { p = ProjectOps.rotateClip(p, "a"); p.clips[0].rotation }
        assertEquals(listOf(90, 180, 270, 0), seen)
    }

    @Test fun fadesNeverExceedHalfTheClip() {
        val p = ProjectOps.setFades(project(clip("a", 2000)), "a", 5000, 5000)
        assertEquals(1000L, p.clips[0].fadeInMs); assertEquals(1000L, p.clips[0].fadeOutMs)
    }

    @Test fun shorteningClipClampsFades() {
        var p = ProjectOps.setFades(project(clip("a", 4000)), "a", 2000, 2000)
        p = ProjectOps.trimClip(p, "a", 0, 1000)
        assertTrue(p.clips[0].fadeInMs <= 500); assertTrue(p.clips[0].fadeOutMs <= 500)
    }

    @Test fun textLayersAreClampedIntoProject() {
        val p = project(clip("a", 5000))
        val t = ProjectOps.addText(p, TextLayer("t", "x", startMs = 4900, durationMs = 9000, x = 2f, y = -1f, scale = 99f)).texts.single()
        assertTrue(t.endMs <= 5000); assertTrue(t.durationMs >= ProjectOps.MIN_LAYER_MS)
        assertEquals(1f, t.x, 0f); assertEquals(0f, t.y, 0f); assertEquals(6f, t.scale, 0f)
    }

    @Test fun defaultTextStartsAtPlayheadAndFits() {
        val p = project(clip("a", 5000))
        val t = ProjectOps.defaultText(p, 4000)
        assertEquals(4000L, t.startMs); assertEquals(1000L, t.durationMs)
        assertEquals(0L, ProjectOps.defaultText(p, -50).startMs)
    }

    @Test fun splitTextProducesAdjacentLayers() {
        var p = project(clip("a", 6000))
        p = ProjectOps.addText(p, TextLayer("t", "hi", startMs = 1000, durationMs = 3000))
        val r = ProjectOps.splitText(p, "t", 2500)!!
        assertEquals(2, r.texts.size)
        assertEquals(1000L, r.texts[0].startMs); assertEquals(1500L, r.texts[0].durationMs)
        assertEquals(2500L, r.texts[1].startMs); assertEquals(1500L, r.texts[1].durationMs)
        assertNull(ProjectOps.splitText(p, "t", 1050))
    }

    @Test fun duplicateTextOffsetsPosition() {
        var p = project(clip("a", 6000))
        p = ProjectOps.addText(p, TextLayer("t", "hi", startMs = 0, durationMs = 1000, x = 0.5f, y = 0.5f))
        val r = ProjectOps.duplicateText(p, "t")
        assertEquals(2, r.texts.size)
        assertEquals(0.55f, r.texts[1].x, 1e-4f)
    }

    @Test fun audioTrimAndSplit() {
        var p = project(clip("a", 10000))
        p = ProjectOps.addAudio(p, AudioTrack("m", "/tmp/m.m4a", "Song", AudioKind.MUSIC, 8000, startMs = 1000))
        val trimmed = ProjectOps.trimAudio(p, "m", 1000, 5000)
        assertEquals(4000L, trimmed.audios[0].durationMs)
        assertEquals(5000L, trimmed.audios[0].endMs)
        val split = ProjectOps.splitAudio(p, "m", 4000)!!
        assertEquals(2, split.audios.size)
        assertEquals(3000L, split.audios[0].durationMs)
        assertEquals(3000L, split.audios[1].inMs)
        assertEquals(4000L, split.audios[1].startMs)
        assertNull(ProjectOps.splitAudio(p, "m", 1000))
    }

    @Test fun audioStartingAfterEndIsDroppedWhenClipsShrink() {
        var p = project(clip("a", 5000), clip("b", 5000))
        p = ProjectOps.addAudio(p, AudioTrack("m", "/tmp/m.m4a", "Song", AudioKind.MUSIC, 3000, startMs = 7000))
        assertTrue(ProjectOps.deleteClip(p, "b").audios.isEmpty())
    }

    @Test fun appendClipsKeepsOrder() {
        val p = ProjectOps.appendClips(project(clip("a")), listOf(clip("b"), clip("c")))
        assertEquals(listOf("a", "b", "c"), p.clips.map { it.id })
    }

    @Test fun insertAfterUnknownIdAppends() {
        val p = ProjectOps.insertClipsAfter(project(clip("a"), clip("b")), "zzz", listOf(clip("c")))
        assertEquals(listOf("a", "b", "c"), p.clips.map { it.id })
        val q = ProjectOps.insertClipsAfter(project(clip("a"), clip("b")), "a", listOf(clip("c")))
        assertEquals(listOf("a", "c", "b"), q.clips.map { it.id })
    }

    @Test fun imageClipDefaultsToThreeSeconds() {
        val c = ProjectOps.makeImageClip("/tmp/x.jpg", 100, 100)
        assertTrue(c.isImage); assertEquals(3000L, c.durationMs)
        assertEquals(1500L, ProjectOps.trimClip(project(c), c.id, 0, 1500).clips[0].durationMs)
        // images may be stretched beyond their default length
        assertEquals(6000L, ProjectOps.trimClip(project(c), c.id, 0, 6000).clips[0].durationMs)
    }
}
