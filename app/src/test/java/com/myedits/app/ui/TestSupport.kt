package com.myedits.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.SurfaceView
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.myedits.app.EditsApp
import com.myedits.app.engine.PreviewController
import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

/** Preview stand-in for JVM tests: tracks play state and position without any codecs. */
class FakePreview : PreviewController {
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing
    override val error: StateFlow<String?> = MutableStateFlow(null)
    override val ended: StateFlow<Boolean> = MutableStateFlow(false)
    override var positionMs: Long = 0L
    override val isPlaying: Boolean get() = _playing.value
    var updates = 0
    var lastProject: Project? = null
    var released = false
    override fun attach(surface: SurfaceView) {}
    override fun detach(surface: SurfaceView) {}
    override fun update(p: Project, positionMs: Long, shortSide: Int) { updates++; lastProject = p }
    override fun play() { _playing.value = true }
    override fun pause() { _playing.value = false }
    override fun seekTo(ms: Long) { positionMs = ms }
    override fun setScrubbing(on: Boolean) {}
    override fun release() { released = true }
}

@UnstableApi
object TestData {
    fun app(): EditsApp = ApplicationProvider.getApplicationContext()

    fun wipe() {
        val c = app().container
        c.repository.list().forEach { c.repository.delete(it.id) }
        c.settings.save(AppSettings())
        c.ideas.save(emptyList())
        app().getSharedPreferences("activity", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Writes a colourful JPEG so thumbnails render something recognisable. */
    fun photo(file: File, w: Int = 1080, h: Int = 1920, hue: Float = 20f): File {
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.95f)), Color.HSVToColor(floatArrayOf((hue + 70) % 360, 0.85f, 0.45f)), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(w * 0.3f, h * 0.35f, w * 0.22f, p)
        c.drawCircle(w * 0.72f, h * 0.62f, w * 0.3f, p)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file
    }

    /** A saved project of [n] 3-second photo clips plus a few layers. */
    fun project(name: String = "Summer trip", n: Int = 3, withLayers: Boolean = false, updatedAt: Long = System.currentTimeMillis(), id: String = ProjectOps.newId()): Project {
        val repo = app().container.repository
        val clips = (0 until n).map { i ->
            val f = photo(File(repo.mediaDir(id), "photo$i.jpg"), hue = 20f + i * 95f)
            ProjectOps.makeImageClip(f.path, 1080, 1920, 3000)
        }
        var p = Project(id, name, updatedAt - 1000, updatedAt, clips = clips)
        if (withLayers) {
            p = ProjectOps.addText(p, ProjectOps.defaultText(p, 500, "Golden hour").copy(y = 0.78f, bg = TextBg.ROUNDED, bgColor = 0xFF000000, font = FontStyleId.BOLD))
            p = ProjectOps.addSticker(p, ProjectOps.defaultSticker(p, 2000, "🔥").copy(x = 0.8f, y = 0.2f))
            val wav = com.myedits.app.audio.SoundLibrary.file(repo.mediaDir(id), "sfx_chime")
            p = ProjectOps.addAudio(p, ProjectOps.makeAudio(p, wav.path, "Chime", AudioKind.SFX, 1800, 1000))
            val music = com.myedits.app.audio.SoundLibrary.file(repo.mediaDir(id), "music_pop")
            p = ProjectOps.addAudio(p, ProjectOps.makeAudio(p, music.path, "Upbeat Pop", AudioKind.MUSIC, 24000, 0).let { it.copy(outMs = 9000) })
        }
        repo.save(p)
        photo(repo.thumbFile(id), 360, 480, 20f)
        return p
    }
}


/** Drives both Robolectric's main looper and Compose's clock until [cond] holds. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        mainClock.advanceTimeByFrame()
        if (cond()) return
        if (System.currentTimeMillis() > end) throw AssertionError("Condition not met within $timeoutMs ms")
        Thread.sleep(25)
    }
}

fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) =
    waitFor(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTagGone(tag: String, timeoutMs: Long = 10_000) =
    waitFor(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) =
    waitFor(timeoutMs) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
