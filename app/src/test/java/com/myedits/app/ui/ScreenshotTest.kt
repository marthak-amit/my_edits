package com.myedits.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myedits.app.data.*
import com.myedits.app.engine.ColorMath
import com.myedits.app.engine.ProjectOps
import com.myedits.app.ui.editor.EditorScreen
import com.myedits.app.ui.home.HomeScreen
import com.myedits.app.ui.settings.SettingsScreen
import com.myedits.app.ui.theme.MyEditsTheme
import com.myedits.app.util.Thumbs
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every main screen to PNG (run with `./gradlew recordRoborazziDebug`). Robolectric cannot
 * decode or display video, so the preview shows the clip's first frame as a still instead.
 */
@OptIn(ExperimentalTestApi::class)
@UnstableApi
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w392dp-h820dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakePreview()

    @Before fun setUp() { TestData.wipe() }
    @After fun tearDown() { TestData.wipe() }

    private fun out(name: String) = File("../screenshots/jvm/$name.png").path
/** Lets async image loads (thumbnails, waveforms) finish before the picture is taken. */
    private fun settle(ms: Long = 1200) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { rule.waitFor { true }; Thread.sleep(40) }
    }

    private fun shot(name: String) { settle(); rule.onRoot().captureRoboImage(out(name)) }
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun click(t: String) { rule.waitForTag(t); runCatching { tag(t).performScrollTo() }; tag(t).performClick(); rule.waitFor { true } }

    private fun home() = rule.setContent { MyEditsTheme { HomeScreen({ _, _ -> }, {}) } }

    private fun editor(p: Project) {
        rule.setContent {
            MyEditsTheme {
                EditorScreen(p.id, false, {}, previewFactory = { fake }, previewSurface = { vm ->
                    val clip = ProjectOps.spanAt(vm.project, vm.playheadMs)?.clip
                    if (clip != null) {
                        val bmp by produceState<android.graphics.Bitmap?>(null, clip.path) { value = Thumbs.frame(clip.path, clip.isImage, clip.inMs, 960) }
                        bmp?.let {
                            Image(
                                it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (clip.fit == FitMode.FILL) ContentScale.Crop else ContentScale.Fit,
                                colorFilter = ColorFilter.colorMatrix(ColorMatrix(ColorMath.toAndroidColorMatrix(ColorMath.clipMatrix(clip.filter, clip.filterIntensity, clip.adjust)))),
                            )
                        }
                    }
                })
            }
        }
        rule.waitForTag("editor_screen")
    }

    private fun seededEditor(): Project {
        val p = TestData.project("Summer trip", n = 3, withLayers = true)
        val styled = ProjectOps.updateClip(p, p.clips[0].id) { it.copy(filter = "sunset", filterIntensity = 0.8f, fadeInMs = 400) }
        TestData.app().container.repository.save(styled)
        return styled
    }

    // ---- Home ----
    @Test fun home_01_empty() { home(); rule.waitForTag("projects_empty"); shot("01_home_empty") }

    @Test fun home_02_projects() {
        TestData.project("Summer trip", n = 3, updatedAt = System.currentTimeMillis() - 120_000)
        TestData.project("Birthday reel", n = 2, updatedAt = System.currentTimeMillis() - 4 * 3_600_000)
        TestData.project("City at night", n = 4, updatedAt = System.currentTimeMillis() - 3 * 86_400_000L)
        TestData.project("Cooking tips", n = 1, updatedAt = System.currentTimeMillis() - 9 * 86_400_000L)
        home(); rule.waitForTag("projects_grid"); shot("02_home_projects")
    }

    @Test fun home_03_create_sheet() { home(); rule.waitForTag("projects_empty"); click("home_create"); rule.waitForTag("create_sheet"); shot("03_home_create_sheet") }

    @Test fun home_04_ideas() {
        val s = TestData.app().container.ideas
        val now = System.currentTimeMillis()
        s.save(listOf(Idea("1", "Sunrise time-lapse from the rooftop", now), Idea("2", "3 quick travel packing hacks", now - 1, done = true), Idea("3", "Day in my life: remote work", now - 2)))
        home(); click("tab_IDEAS"); rule.waitForTag("ideas_list"); shot("04_home_ideas")
    }

    @Test fun home_05_inspiration() { home(); click("tab_TEMPLATES"); rule.waitForTag("inspiration_list"); shot("05_home_inspiration") }

    @Test fun home_06_insights() {
        TestData.project("Summer trip", n = 3); TestData.project("Birthday reel", n = 2)
        val log = TestData.app().container.activity
        val now = System.currentTimeMillis(); val day = 86_400_000L
        listOf(0 to "created", 0 to "exported", 1 to "exported", 2 to "created", 2 to "exported", 2 to "exported", 4 to "created", 5 to "exported").forEach { (d, t) -> log.record(t, 8000, now - d * day) }
        home(); click("tab_INSIGHTS"); rule.waitForTag("insights"); shot("06_home_insights")
    }

    @Test fun home_07_settings() { rule.setContent { MyEditsTheme { SettingsScreen({}, {}) } }; shot("07_settings") }

    // ---- Editor ----
    @Test fun editor_01_main() { editor(seededEditor()); shot("10_editor_main") }

    @Test fun editor_02_clip_selected() { val p = seededEditor(); editor(p); click("tool_edit"); shot("11_editor_clip_tools") }

    @Test fun editor_03_text_selected() { val p = seededEditor(); editor(p); click("lane_${p.texts.single().id}"); shot("12_editor_text_selected") }

    @Test fun editor_04_text_style() { val p = seededEditor(); editor(p); click("lane_${p.texts.single().id}"); click("tool_text_style"); rule.waitForTag("panel_text_style"); shot("13_editor_text_style") }

    @Test fun editor_05_filters() { editor(seededEditor()); click("tool_filters"); rule.waitForTag("panel_filter"); shot("14_editor_filters") }

    @Test fun editor_06_adjust() {
        editor(seededEditor()); click("tool_adjust"); rule.waitForTag("panel_adjust")
        listOf("Brightness" to 0.2f, "Contrast" to 0.3f, "Saturation" to 0.4f).forEach { (n, v) -> tag("slider_$n").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(v) } }
        shot("15_editor_adjust")
    }

    @Test fun editor_07_stickers() { editor(seededEditor()); click("tool_stickers"); rule.waitForTag("panel_stickers"); shot("16_editor_stickers") }

    @Test fun editor_08_sounds() { editor(seededEditor()); click("tool_audio"); click("audio_sounds"); rule.waitForTag("panel_sounds"); shot("17_editor_sounds") }

    @Test fun editor_09_audio_menu() { editor(seededEditor()); click("tool_audio"); rule.waitForTag("panel_audio"); shot("18_editor_audio_menu") }

    @Test fun editor_10_canvas() { editor(seededEditor()); click("tool_canvas"); rule.waitForTag("panel_canvas"); shot("19_editor_canvas") }

    @Test fun editor_11_audio_selected() {
        val p = seededEditor(); editor(p)
        click("lane_${p.audios.first { it.kind == AudioKind.MUSIC }.id}"); click("tool_volume"); rule.waitForTag("panel_volume"); shot("20_editor_audio_volume")
    }

    @Test fun editor_12_export_sheet() { editor(seededEditor()); click("editor_export"); rule.waitForTag("export_sheet"); shot("21_editor_export") }

    @Test fun editor_13_empty_project() {
        val now = System.currentTimeMillis(); val p = Project("empty1", "Untitled", now, now); TestData.app().container.repository.save(p)
        editor(p); shot("22_editor_empty")
    }
    // ---- Templates, transitions, effects ----
    @Test fun home_08_template_sheet() { home(); click("tab_TEMPLATES"); rule.waitForTag("inspiration_list"); click("template_beat_drop"); rule.waitForTag("template_sheet"); shot("08_home_template_sheet") }

    @Test fun editor_14_transition() { editor(seededEditor()); click("tool_edit"); click("tool_transition"); rule.waitForTag("panel_transition"); click("transition_ZOOM"); shot("23_editor_transition") }

    @Test fun editor_15_animate() { editor(seededEditor()); click("tool_edit"); click("tool_animate"); rule.waitForTag("panel_animate"); click("anim_ZOOM_IN"); shot("24_editor_animate") }

    @Test fun editor_16_effects() {
        editor(seededEditor()); click("tool_edit"); click("tool_effects"); rule.waitForTag("panel_effects")
        click("fx_bars"); tag("slider_vignette").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.7f) }
        shot("25_editor_effects")
    }

    @Test fun editor_17_position() {
        editor(seededEditor()); click("tool_edit"); click("tool_position"); rule.waitForTag("panel_position")
        tag("slider_zoom").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.6f) }
        shot("26_editor_position")
    }

    @Test fun editor_18_text_animation() {
        val p = seededEditor(); editor(p); click("lane_${p.texts.single().id}"); click("tool_layer_anim"); rule.waitForTag("panel_layer_anim"); click("layer_anim_POP"); shot("27_editor_text_animation")
    }

    @Test fun editor_19_captions() { editor(seededEditor()); click("tool_captions"); rule.waitForTag("panel_captions"); shot("28_editor_captions") }

    @Test fun editor_20_voice() {
        val p = seededEditor(); editor(p)
        click("lane_${p.audios.first { it.kind == AudioKind.SFX }.id}"); click("tool_voice"); rule.waitForTag("panel_voice"); click("voice_deep"); shot("29_editor_voice")
    }

    @Test fun editor_21_from_template() {
        val base = TestData.project("Glow Up", n = 3)
        val sound: (String, Long) -> AudioTrack? = { id, start ->
            com.myedits.app.audio.SoundLibrary.byId(id)?.let { ProjectOps.makeAudio(base, com.myedits.app.audio.SoundLibrary.file(TestData.app().container.repository.mediaDir(base.id), id).path, it.label, AudioKind.MUSIC, it.durationMs, start) }
        }
        val p = Templates.apply(base, Templates.byId("travel_diary")!!, base.clips, sound)
        TestData.app().container.repository.save(p)
        editor(p); shot("30_editor_from_template")
    }
}
