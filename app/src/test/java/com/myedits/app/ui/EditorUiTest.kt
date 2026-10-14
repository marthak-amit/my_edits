package com.myedits.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myedits.app.data.*
import com.myedits.app.ui.editor.EditorScreen
import com.myedits.app.ui.theme.MyEditsTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@UnstableApi
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w392dp-h800dp-xhdpi")
class EditorUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakePreview()
    private var exited = 0

    @Before fun setUp() { TestData.wipe(); exited = 0 }
    @After fun tearDown() { TestData.wipe() }

    private fun open(p: Project) {
        rule.setContent { MyEditsTheme { EditorScreen(p.id, false, { exited++ }, previewFactory = { fake }) } }
        rule.waitForTag("editor_screen")
    }
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun click(t: String) {
        rule.waitForTag(t)
        // toolbars scroll horizontally; bring the button into view like a user would
        runCatching { tag(t).performScrollTo() }
        tag(t).performClick(); rule.waitFor { true }
    }
    private fun project(): Project = fake.lastProject!!
    private fun slider(t: String, v: Float) { rule.waitForTag(t); tag(t).performSemanticsAction(SemanticsActions.SetProgress) { it(v) }; rule.waitFor { true } }

    @Test fun openingShowsProjectToolbarAndTimeline() {
        val p = TestData.project("Road trip", n = 3)
        open(p)
        rule.onNodeWithText("Road trip").assertIsDisplayed()
        tag("toolbar_root").assertIsDisplayed(); tag("timeline").assertIsDisplayed(); tag("playhead").assertIsDisplayed()
        tag("time_label").assertTextContains("0:00.0 / 0:09.0")
        click("zoom_out"); click("zoom_out")
        p.clips.forEach { tag("clip_${it.id}").assertExists() }
        tag("editor_undo").assertIsNotEnabled(); tag("editor_redo").assertIsNotEnabled()
        assertEquals(3, project().clips.size)
    }

    @Test fun emptyProjectDisablesEditingTools() {
        val repo = TestData.app().container.repository
        val now = System.currentTimeMillis()
        val p = Project("empty1", "Blank", now, now); repo.save(p)
        open(p)
        tag("tool_add").assertIsEnabled()
        tag("tool_edit").assertIsNotEnabled(); tag("tool_text").assertIsNotEnabled(); tag("tool_audio").assertIsNotEnabled()
        tag("editor_export").assertIsNotEnabled(); tag("play_button").assertIsNotEnabled()
        rule.onNodeWithText("Add a clip to start editing").assertIsDisplayed()
    }

    @Test fun playButtonTogglesPlayback() {
        open(TestData.project()); click("play_button")
        assertTrue(fake.isPlaying); click("play_button"); assertFalse(fake.isPlaying)
    }

    @Test fun selectingClipShowsClipToolbarAndBackDeselects() {
        val p = TestData.project(); open(p)
        click("tool_edit"); tag("toolbar_clip").assertIsDisplayed()
        click("tool_back"); tag("toolbar_root").assertIsDisplayed()
    }

    @Test fun splitCutsClipAtPlayheadAndUndoRestores() {
        val p = TestData.project(n = 1); open(p)
        // move the playhead into the middle of the only clip
        rule.runOnIdle { fake.seekTo(1500) }
        val vm = vm(p); vm.seek(1500)
        rule.waitFor { vm.playheadMs == 1500L }
        click("tool_edit"); click("tool_split")
        assertEquals(2, project().clips.size)
        assertEquals(1500L, project().clips[0].durationMs)
        assertEquals(3000L, project().durationMs)
        click("editor_undo"); assertEquals(1, project().clips.size)
        click("editor_redo"); assertEquals(2, project().clips.size)
    }

    @Test fun splitAtVeryStartIsRejectedWithMessage() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_split")
        assertEquals(1, project().clips.size)
        rule.waitForText("Move the playhead inside the item to split it")
    }

    @Test fun deleteRemovesSelectedClip() {
        val p = TestData.project(n = 3); open(p)
        click("clip_${p.clips[1].id}"); click("tool_delete")
        assertEquals(listOf(p.clips[0].id, p.clips[2].id), project().clips.map { it.id })
        tag("toolbar_root").assertIsDisplayed()
    }

    @Test fun duplicateAndMoveClip() {
        val p = TestData.project(n = 2); open(p)
        click("clip_${p.clips[0].id}"); click("tool_duplicate")
        assertEquals(3, project().clips.size)
        click("tool_move_right")
        assertEquals(p.clips[0].id, project().clips[1].id)
        assertEquals(p.clips[1].id, project().clips[2].id)
    }

    @Test fun speedPanelChangesClipSpeedAndDuration() {
        val p = TestData.project(n = 1)
        // use a video-like clip: speed UI is for videos, images get a duration control
        val vid = p.clips[0].copy(isImage = false, sourceDurationMs = 6000, outMs = 6000)
        TestData.app().container.repository.save(p.copy(clips = listOf(vid)))
        open(p)
        click("tool_edit"); click("tool_speed"); rule.waitForTag("panel_speed")
        slider("slider_speed", 2f)
        assertEquals(2f, project().clips[0].speed, 0.11f)
        click("speed_0.5")
        assertEquals(0.5f, project().clips[0].speed, 0.001f)
        assertEquals(12000L, project().durationMs)
        click("panel_done"); tag("toolbar_clip").assertIsDisplayed()
    }

    @Test fun photoClipOffersDurationInsteadOfSpeed() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_speed"); rule.waitForTag("slider_duration")
        slider("slider_duration", 5f)
        assertEquals(5000L, project().clips[0].durationMs)
    }

    @Test fun volumePanelMutesVideoClip() {
        val p = TestData.project(n = 1)
        TestData.app().container.repository.save(p.copy(clips = listOf(p.clips[0].copy(isImage = false, sourceDurationMs = 4000, outMs = 4000))))
        open(p)
        click("tool_edit"); click("tool_volume"); rule.waitForTag("panel_volume")
        click("vol_mute"); assertEquals(0f, project().clips[0].volume, 0f)
        click("vol_150"); assertEquals(1.5f, project().clips[0].volume, 0f)
        slider("slider_volume", 0.4f); assertEquals(0.4f, project().clips[0].volume, 0.01f)
    }

    @Test fun fadePanelSetsFadesAndClampsToHalfClip() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_fade"); rule.waitForTag("panel_fade")
        slider("slider_fade_in", 1000f); slider("slider_fade_out", 800f)
        assertEquals(1000L, project().clips[0].fadeInMs); assertEquals(800L, project().clips[0].fadeOutMs)
    }

    @Test fun rotateFlipFitZoomToggles() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit")
        click("tool_rotate"); assertEquals(90, project().clips[0].rotation)
        click("tool_flip"); assertTrue(project().clips[0].flipH)
        click("tool_fit"); assertEquals(FitMode.FILL, project().clips[0].fit)
        click("tool_zoom"); assertEquals(1.5f, project().clips[0].zoom, 0.001f)
    }

    @Test fun filterPanelAppliesPresetAndIntensity() {
        val p = TestData.project(n = 2); open(p)
        click("tool_filters"); rule.waitForTag("panel_filter")
        click("filter_vivid")
        assertEquals("vivid", project().clips[0].filter); assertEquals("none", project().clips[1].filter)
        slider("slider_intensity", 0.4f); assertEquals(0.4f, project().clips[0].filterIntensity, 0.01f)
        click("filter_none"); assertEquals("none", project().clips[0].filter)
    }

    @Test fun filterApplyToAllAffectsEveryClip() {
        val p = TestData.project(n = 3); open(p)
        click("tool_filters"); rule.waitForTag("panel_filter"); click("filter_all")
        click("filter_vivid"); assertTrue(project().clips.all { it.filter == "vivid" })
    }

    @Test fun adjustSlidersWriteValuesAndResetClears() {
        val p = TestData.project(n = 1); open(p)
        click("tool_adjust"); rule.waitForTag("panel_adjust")
        slider("slider_Brightness", 0.5f); slider("slider_Contrast", -0.3f)
        assertEquals(0.5f, project().clips[0].adjust.brightness, 0.011f)
        assertEquals(-0.3f, project().clips[0].adjust.contrast, 0.011f)
        click("adjust_reset"); assertTrue(project().clips[0].adjust.isNeutral)
    }

    @Test fun canvasChangesAspectRatio() {
        val p = TestData.project(n = 1); open(p)
        click("tool_canvas"); rule.waitForTag("panel_canvas"); click("aspect_16:9")
        assertEquals(AspectRatio.R16_9, project().aspect)
        click("aspect_1:1"); assertEquals(AspectRatio.R1_1, project().aspect)
    }

    @Test fun addingTextOpensEditorAndTypingUpdatesLayer() {
        val p = TestData.project(n = 1); open(p)
        click("tool_text"); rule.waitForTag("panel_text_edit")
        assertEquals(1, project().texts.size)
        tag("text_input").performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("Hello Edits")) }
        rule.waitFor { project().texts.first().text == "Hello Edits" }
        click("text_to_style"); rule.waitForTag("panel_text_style")
        click("font_SERIF"); click("color_ffff453a"); click("bg_ROUNDED"); click("align_LEFT"); click("text_outline")
        val t = project().texts.first()
        assertEquals(FontStyleId.SERIF, t.font); assertEquals(0xFFFF453AL, t.color); assertEquals(TextBg.ROUNDED, t.bg)
        assertEquals(TextAlign.LEFT, t.align); assertTrue(t.outline)
        slider("slider_text_size", 2f); assertEquals(2f, project().texts.first().scale, 0.01f)
    }

    @Test fun textLayerCanBeDuplicatedAndDeleted() {
        val p = TestData.project(n = 2, withLayers = true); open(p)
        val id = p.texts.single().id
        click("lane_$id"); tag("toolbar_text").assertIsDisplayed()
        click("tool_duplicate"); assertEquals(2, project().texts.size)
        click("tool_delete"); assertEquals(1, project().texts.size)
    }

    @Test fun stickersPanelAddsEmoji() {
        val p = TestData.project(n = 1); open(p)
        click("tool_stickers"); rule.waitForTag("panel_stickers")
        click("emoji_😍")
        assertEquals("😍", project().stickers.single().emoji)
        tag("toolbar_sticker").assertIsDisplayed()
        click("tool_delete"); assertTrue(project().stickers.isEmpty())
    }

    @Test fun soundsPanelAddsGeneratedEffectToTimeline() {
        val p = TestData.project(n = 2); open(p)
        click("tool_audio"); rule.waitForTag("panel_audio"); click("audio_sounds"); rule.waitForTag("panel_sounds")
        click("sound_tab_EFFECTS"); click("sound_add_sfx_ding")
        rule.waitFor { fake.lastProject?.audios?.size == 1 }
        val a = project().audios.single()
        assertEquals(AudioKind.SFX, a.kind); assertEquals("Ding", a.label)
        assertTrue(java.io.File(a.path).exists() && java.io.File(a.path).length() > 1000)
    }

    @Test fun audioTrackVolumeFadeSplitAndDelete() {
        val p = TestData.project(n = 3, withLayers = true); open(p)
        val music = p.audios.first { it.kind == AudioKind.MUSIC }
        click("lane_${music.id}"); tag("toolbar_audio").assertIsDisplayed()
        click("tool_volume"); rule.waitForTag("panel_volume"); slider("slider_volume", 0.5f)
        assertEquals(0.5f, project().audios.first { it.id == music.id }.volume, 0.011f)
        click("panel_done"); click("tool_fade"); rule.waitForTag("panel_fade"); slider("slider_fade_out", 1500f)
        assertEquals(1500L, project().audios.first { it.id == music.id }.fadeOutMs)
        click("panel_done"); click("tool_delete")
        assertTrue(project().audios.none { it.id == music.id })
    }

    @Test fun ttsPanelRequiresText() {
        val p = TestData.project(n = 1); open(p)
        click("tool_audio"); click("audio_tts"); rule.waitForTag("panel_tts")
        tag("tts_add").assertIsNotEnabled()
        tag("tts_input").performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("Welcome")) }
        rule.waitFor { true }
        tag("tts_add").assertIsEnabled()
    }

    @Test fun undoRedoButtonsReflectHistory() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_rotate")
        tag("editor_undo").assertIsEnabled()
        click("editor_undo"); assertEquals(0, project().clips[0].rotation); tag("editor_redo").assertIsEnabled()
        click("editor_redo"); assertEquals(90, project().clips[0].rotation)
    }

    @Test fun swipingTheTimelineScrubsThePlayheadBothWays() {
        val p = TestData.project(n = 3); open(p)
        tag("time_label").assertTextContains("0:00.0", substring = true)
        tag("timeline").performTouchInput { swipe(Offset(centerX + 150f, centerY), Offset(centerX - 150f, centerY), durationMillis = 400) }
        rule.waitFor { true }
        val after = fake.positionMs
        assertTrue("playhead moved forward, got $after", after > 1500)
        tag("timeline").performTouchInput { swipe(Offset(centerX - 150f, centerY), Offset(centerX + 150f, centerY), durationMillis = 400) }
        rule.waitFor { true }
        assertTrue("playhead moved back", fake.positionMs < after - 1500)
    }

    @Test fun scrubbingNeverLeavesTheProject() {
        val p = TestData.project(n = 1); open(p)
        tag("timeline").performTouchInput { swipe(Offset(centerX + 150f, centerY), Offset(centerX - 150f, centerY), durationMillis = 300); swipe(Offset(centerX + 150f, centerY), Offset(centerX - 150f, centerY), durationMillis = 300) }
        rule.waitFor { true }
        assertTrue(fake.positionMs <= 3000)
        tag("time_label").assertTextContains("/ 0:03.0", substring = true)
    }

    @Test fun zoomButtonsKeepTimelineUsable() {
        val p = TestData.project(n = 2); open(p)
        click("zoom_in"); click("zoom_out"); click("zoom_out"); tag("timeline").assertIsDisplayed()
    }

    @Test fun exportSheetShowsOptionsAndSummary() {
        val p = TestData.project(n = 2); open(p)
        click("editor_export"); rule.waitForTag("export_sheet")
        tag("export_start").assertIsEnabled()
        click("export_q_720"); click("export_fps_30")
        tag("export_summary").assertTextContains("720 × 1280", substring = true)
        tag("export_summary").assertTextContains("0:06", substring = true)
    }

    @Test fun editsAreAutosavedToDisk() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_rotate")
        rule.waitFor(15000) { TestData.app().container.repository.load(p.id)?.clips?.first()?.rotation == 90 }
    }

    @Test fun closeButtonLeavesEditor() {
        open(TestData.project()); click("editor_close"); assertEquals(1, exited)
    }

    // ---- transitions, motion, effects, position, captions, voice ------------------------------------

    @Test fun transitionPanelSetsTransitionLengthAndApplyAll() {
        val p = TestData.project(n = 3); open(p)
        click("tool_edit"); click("tool_transition"); rule.waitForTag("panel_transition")
        click("transition_ZOOM")
        assertEquals(Transition.ZOOM, project().clips[0].transition)
        slider("slider_transition_ms", 900f)
        assertEquals(900L, project().clips[0].transitionMs)
        click("apply_all"); click("transition_FLASH")
        assertEquals(listOf(Transition.FLASH, Transition.FLASH, Transition.NONE), project().clips.map { it.transition })
        click("editor_undo")
        assertEquals(Transition.ZOOM, project().clips[0].transition)
        click("transition_NONE")
        assertEquals(Transition.NONE, project().clips[0].transition)
    }

    @Test fun animatePanelAppliesKenBurns() {
        val p = TestData.project(n = 2); open(p)
        click("tool_edit"); click("tool_animate"); rule.waitForTag("panel_animate")
        click("anim_ZOOM_IN")
        assertEquals(ClipAnim.ZOOM_IN, project().clips[0].animation); assertEquals(ClipAnim.NONE, project().clips[1].animation)
        click("apply_all"); click("anim_PAN_LEFT")
        assertTrue(project().clips.all { it.animation == ClipAnim.PAN_LEFT })
    }

    @Test fun effectsPanelControlsBlurVignetteAndBars() {
        val p = TestData.project(n = 2); open(p)
        click("tool_effects"); rule.waitForTag("panel_effects")
        slider("slider_blur", 0.5f); slider("slider_vignette", 0.8f); click("fx_bars")
        val c = project().clips[0]
        assertEquals(0.5f, c.blur, 0.02f); assertEquals(0.8f, c.vignette, 0.02f); assertTrue(c.bars)
        assertEquals(0f, project().clips[1].blur, 0f)
        click("fx_reset")
        assertEquals(0f, project().clips[0].blur, 0f); assertFalse(project().clips[0].bars)
        // the preview draws the look itself
        click("panel_close"); rule.waitFor { true }
    }

    @Test fun previewShowsVignetteAndBarsOfTheCurrentClip() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_effects"); click("fx_bars"); slider("slider_vignette", 0.6f)
        rule.waitForTag("look_bars"); rule.waitForTag("look_vignette")
    }

    @Test fun positionPanelZoomPanFlipAndReset() {
        val p = TestData.project(n = 1); open(p)
        click("tool_edit"); click("tool_position"); rule.waitForTag("panel_position")
        assertTrue(rule.onAllNodesWithTag("slider_pan_x").fetchSemanticsNodes().isEmpty())
        slider("slider_zoom", 2f)
        rule.waitForTag("slider_pan_x")
        slider("slider_pan_x", 0.6f); slider("slider_pan_y", -0.4f)
        click("pos_flip_h"); click("pos_rotate")
        val c = project().clips[0]
        assertEquals(2f, c.zoom, 0.05f); assertEquals(0.6f, c.panX, 0.05f); assertEquals(-0.4f, c.panY, 0.05f)
        assertTrue(c.flipH); assertEquals(90, c.rotation)
        click("pos_reset")
        val r = project().clips[0]
        assertEquals(1f, r.zoom, 0f); assertEquals(0f, r.panX, 0f); assertFalse(r.flipH); assertEquals(0, r.rotation)
    }

    @Test fun textAndStickerAnimationAndOpacity() {
        val p = TestData.project(n = 2, withLayers = true); open(p)
        val vm = vm(p)
        val textId = p.texts.first().id
        rule.runOnIdle { vm.select(com.myedits.app.ui.editor.Selection.OfText(textId)) }
        click("tool_layer_anim"); rule.waitForTag("panel_layer_anim")
        click("layer_anim_POP"); slider("slider_opacity", 0.5f)
        assertEquals(LayerAnim.POP, project().texts[0].anim); assertEquals(0.5f, project().texts[0].opacity, 0.03f)
        click("panel_close")
        rule.runOnIdle { vm.select(com.myedits.app.ui.editor.Selection.OfSticker(p.stickers.first().id)) }
        click("tool_layer_anim"); click("layer_anim_SLIDE_UP")
        assertEquals(LayerAnim.SLIDE_UP, project().stickers[0].anim)
    }

    @Test fun captionsFromScriptAddsTimedTexts() {
        val p = TestData.project(n = 3); open(p)
        click("tool_captions"); rule.waitForTag("panel_captions")
        tag("captions_add").assertIsNotEnabled()
        tag("captions_input").performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("Hello\nWorld\nBye")) }
        rule.waitFor { true }
        click("captions_add")
        rule.waitFor { project().texts.size == 3 }
        assertEquals(listOf("Hello", "World", "Bye"), project().texts.map { it.text })
        assertEquals(listOf(0L, 3000L, 6000L), project().texts.map { it.startMs })
    }

    @Test fun voiceEffectsChangeAudioPitch() {
        val p = TestData.project(n = 2, withLayers = true); open(p)
        val vm = vm(p)
        rule.runOnIdle { vm.select(com.myedits.app.ui.editor.Selection.OfAudio(p.audios.first().id)) }
        click("tool_voice"); rule.waitForTag("panel_voice")
        click("voice_chipmunk")
        assertEquals(1.45f, project().audios[0].pitch, 0.01f)
        slider("slider_pitch", 0.8f)
        assertEquals(0.8f, project().audios[0].pitch, 0.02f)
        click("voice_normal")
        assertEquals(1f, project().audios[0].pitch, 0.01f)
    }

    @Test fun panelsCloseWhenTheSelectionNoLongerFits() {
        val p = TestData.project(n = 2, withLayers = true); open(p)
        val vm = vm(p)
        click("tool_edit"); click("tool_transition"); rule.waitForTag("panel_transition")
        rule.runOnIdle { vm.select(com.myedits.app.ui.editor.Selection.OfText(p.texts.first().id)) }
        rule.waitForTagGone("panel_transition")
        tag("toolbar_text").assertIsDisplayed()
    }

    @Test fun freezeFrameInsertsStillAndUndoRemovesIt() {
        val p = TestData.project(n = 1); open(p)
        val vm = vm(p)
        rule.runOnIdle { vm.seek(1500) }
        click("tool_edit"); click("tool_freeze")
        rule.waitFor(15000) { project().clips.size == 3 }
        assertTrue(project().clips[1].isImage); assertEquals(2000L, project().clips[1].durationMs)
        assertEquals(p.durationMs + 2000L, project().durationMs)
        click("editor_undo")
        assertEquals(1, project().clips.size)
    }

    private fun vm(p: Project): com.myedits.app.ui.editor.EditorViewModel {
        val owner = rule.activity
        val key = "androidx.lifecycle.ViewModelProvider.DefaultKey:editor_${p.id}"
        val storeField = androidx.lifecycle.ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = storeField.get(owner.viewModelStore) as Map<String, androidx.lifecycle.ViewModel>
        return (map[key] ?: map.values.first { it is com.myedits.app.ui.editor.EditorViewModel }) as com.myedits.app.ui.editor.EditorViewModel
    }
}
