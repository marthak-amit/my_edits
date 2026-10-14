package com.myedits.app

import com.myedits.app.audio.SoundLibrary
import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryAndSoundTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sample(id: String, dir: File): Project {
        val media = File(dir, "projects/$id/media").apply { mkdirs() }
        val f = File(media, "a.mp4").apply { writeText("video") }
        return Project(
            id = id, name = "Draft", createdAt = 1, updatedAt = 2,
            clips = listOf(ProjectOps.makeVideoClip(f.path, 5000, 1080, 1920)),
            texts = listOf(TextLayer("t", "Hello\nWorld", 0, 1000, color = 0xFFFF0000, font = FontStyleId.SERIF, bg = TextBg.ROUNDED)),
            stickers = listOf(StickerLayer("s", StickerKind.EMOJI, emoji = "😀", startMs = 0, durationMs = 500)),
            audios = listOf(AudioTrack("m", f.path, "Song", AudioKind.MUSIC, 4000)),
        )
    }

    @Test fun saveLoadRoundTripsEverything() {
        val repo = ProjectRepository(tmp.root)
        val p = sample("abc", tmp.root)
        repo.save(p)
        assertEquals(p, repo.load("abc"))
    }

    @Test fun listIsNewestFirst() {
        val repo = ProjectRepository(tmp.root)
        repo.save(sample("old", tmp.root).copy(updatedAt = 10))
        repo.save(sample("new", tmp.root).copy(updatedAt = 99))
        assertEquals(listOf("new", "old"), repo.list().map { it.id })
    }

    @Test fun corruptProjectIsSkippedNotCrashing() {
        val repo = ProjectRepository(tmp.root)
        repo.save(sample("good", tmp.root))
        File(repo.projectDir("bad"), "project.json").writeText("{not json")
        assertEquals(listOf("good"), repo.list().map { it.id })
        assertNull(repo.load("bad")); assertNull(repo.load("nonexistent"))
    }

    @Test fun deleteRemovesDirectoryWithMedia() {
        val repo = ProjectRepository(tmp.root)
        repo.save(sample("x", tmp.root))
        repo.delete("x")
        assertNull(repo.load("x")); assertFalse(File(tmp.root, "projects/x").exists())
    }

    @Test fun renameTrimsAndIgnoresBlank() {
        val repo = ProjectRepository(tmp.root)
        repo.save(sample("x", tmp.root))
        repo.rename("x", "  My Reel  "); assertEquals("My Reel", repo.load("x")!!.name)
        repo.rename("x", "   "); assertEquals("My Reel", repo.load("x")!!.name)
    }

    @Test fun duplicateCopiesMediaAndRewritesPaths() {
        val repo = ProjectRepository(tmp.root)
        repo.save(sample("src", tmp.root))
        val copy = repo.duplicate("src", "dst", "Draft copy")!!
        assertEquals("Draft copy", copy.name)
        val newPath = copy.clips[0].path
        assertTrue(newPath, newPath.contains("/projects/dst/"))
        assertTrue(File(newPath).exists())
        assertTrue(copy.audios[0].path.contains("/projects/dst/"))
        // original is untouched
        assertTrue(repo.load("src")!!.clips[0].path.contains("/projects/src/"))
        assertNull(repo.duplicate("missing", "z", "z"))
    }

    @Test fun loadingToleratesUnknownJsonKeys() {
        val repo = ProjectRepository(tmp.root)
        repo.save(sample("x", tmp.root))
        val f = File(tmp.root, "projects/x/project.json")
        f.writeText(f.readText().replaceFirst("{", "{\"futureField\":123,"))
        assertNotNull(repo.load("x"))
    }

    @Test fun settingsDefaults() {
        val s = AppSettings()
        assertEquals(1080, s.exportQuality); assertEquals(0, s.exportFps); assertTrue(s.saveToGallery)
    }

    @Test fun everySoundRendersToAValidNonSilentWav() {
        for (item in SoundLibrary.items) {
            val f = SoundLibrary.file(tmp.newFolder(), item.id)
            val bytes = f.readBytes()
            assertEquals(item.id, "RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            val expectedSamples = (item.durationMs * SoundLibrary.SAMPLE_RATE / 1000).toInt()
            assertEquals(item.id, 44 + expectedSamples * 2, bytes.size)
            var peak = 0
            for (i in 44 until bytes.size step 2) {
                val v = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(v.toShort().toInt()))
            }
            assertTrue("${item.id} silent", peak > 3000)
            assertTrue("${item.id} clipping", peak <= 32767)
        }
    }

    @Test fun soundFileIsCachedOnSecondCall() {
        val dir = tmp.newFolder()
        val a = SoundLibrary.file(dir, "sfx_pop"); val t = a.lastModified()
        Thread.sleep(20)
        assertEquals(t, SoundLibrary.file(dir, "sfx_pop").lastModified())
    }
}
