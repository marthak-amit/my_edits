package com.myedits.app.ui.editor

import android.media.MediaPlayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.myedits.app.audio.SoundCategory
import com.myedits.app.audio.SoundLibrary
import com.myedits.app.data.*
import com.myedits.app.engine.ColorMath
import com.myedits.app.engine.FilterPresets
import com.myedits.app.engine.ProjectOps
import com.myedits.app.ui.common.*
import com.myedits.app.ui.theme.EditsColors
import com.myedits.app.util.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Hooks into Activity-result launchers owned by the screen. */
class EditorActions(
    val pickMedia: () -> Unit,
    val pickOverlay: () -> Unit,
    val pickAudio: () -> Unit,
    val startVoice: () -> Unit,
    val openCamera: () -> Unit,
)

@UnstableApi
@Composable
fun EditorBottom(vm: EditorViewModel, actions: EditorActions, modifier: Modifier = Modifier) {
    val panel = vm.panel
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth().background(EditsColors.Surface).then(if (panel != Panel.NONE) Modifier.verticalScroll(scroll) else Modifier)) {
        when (panel) {
            Panel.NONE -> when (vm.selection) {
                Selection.None -> RootToolbar(vm, actions)
                is Selection.OfClip -> ClipToolbar(vm)
                is Selection.OfText -> TextToolbar(vm)
                is Selection.OfSticker -> StickerToolbar(vm)
                is Selection.OfAudio -> AudioToolbar(vm)
            }
            Panel.SPEED -> SpeedPanel(vm)
            Panel.VOLUME -> VolumePanel(vm)
            Panel.FADE -> FadePanel(vm)
            Panel.FILTER -> FilterPanel(vm)
            Panel.ADJUST -> AdjustPanel(vm)
            Panel.CANVAS -> CanvasPanel(vm)
            Panel.AUDIO -> AudioMenuPanel(vm, actions)
            Panel.SOUNDS -> SoundsPanel(vm)
            Panel.TEXT_STYLE -> TextStylePanel(vm)
            Panel.TEXT_EDIT -> TextEditPanel(vm)
            Panel.STICKERS -> StickersPanel(vm, actions)
            Panel.TTS -> TtsPanel(vm)
            Panel.EXPORT -> Unit // shown as a sheet
        }
    }
}

// ---- toolbars ---------------------------------------------------------------------------------

@Composable
private fun ToolRow(modifier: Modifier = Modifier, content: LazyRowScopeContent) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}
private typealias LazyRowScopeContent = @Composable RowScope.() -> Unit

@UnstableApi
@Composable
private fun RootToolbar(vm: EditorViewModel, a: EditorActions) {
    val has = !vm.project.isEmpty
    ToolRow(Modifier.testTag("toolbar_root")) {
        ToolButton(Icons.Filled.AddPhotoAlternate, "Add", a.pickMedia, tag = "tool_add")
        ToolButton(Icons.Filled.Videocam, "Camera", a.openCamera, tag = "tool_camera")
        ToolButton(Icons.Filled.ContentCut, "Edit", { vm.selectClipAtPlayhead() }, enabled = has, tag = "tool_edit")
        ToolButton(Icons.Filled.MusicNote, "Audio", { vm.openPanel(Panel.AUDIO) }, enabled = has, tag = "tool_audio")
        ToolButton(Icons.Filled.TextFields, "Text", { vm.addText() }, enabled = has, tag = "tool_text")
        ToolButton(Icons.Filled.EmojiEmotions, "Stickers", { vm.openPanel(Panel.STICKERS) }, enabled = has, tag = "tool_stickers")
        ToolButton(Icons.Filled.PictureInPicture, "Overlay", a.pickOverlay, enabled = has, tag = "tool_overlay")
        ToolButton(Icons.Filled.AutoAwesome, "Filters", { vm.selectClipAtPlayhead(); vm.openPanel(Panel.FILTER) }, enabled = has, tag = "tool_filters")
        ToolButton(Icons.Filled.Tune, "Adjust", { vm.selectClipAtPlayhead(); vm.openPanel(Panel.ADJUST) }, enabled = has, tag = "tool_adjust")
        ToolButton(Icons.Filled.AspectRatio, "Canvas", { vm.openPanel(Panel.CANVAS) }, enabled = has, tag = "tool_canvas")
    }
}

@UnstableApi
@Composable
private fun ClipToolbar(vm: EditorViewModel) {
    val c = vm.selectedClip ?: return
    val idx = vm.project.clips.indexOfFirst { it.id == c.id }
    ToolRow(Modifier.testTag("toolbar_clip")) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { vm.select(Selection.None) }, tag = "tool_back")
        ToolButton(Icons.Filled.ContentCut, "Split", { vm.split() }, tag = "tool_split")
        if (c.isImage) ToolButton(Icons.Filled.Timer, "Duration", { vm.openPanel(Panel.SPEED) }, tag = "tool_speed")
        else ToolButton(Icons.Filled.Speed, "Speed", { vm.openPanel(Panel.SPEED) }, tag = "tool_speed")
        if (!c.isImage) ToolButton(if (c.volume == 0f) Icons.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Volume", { vm.openPanel(Panel.VOLUME) }, tag = "tool_volume")
        ToolButton(Icons.Filled.Gradient, "Fade", { vm.openPanel(Panel.FADE) }, tag = "tool_fade")
        ToolButton(Icons.Filled.AutoAwesome, "Filters", { vm.openPanel(Panel.FILTER) }, tag = "tool_filters")
        ToolButton(Icons.Filled.Tune, "Adjust", { vm.openPanel(Panel.ADJUST) }, tag = "tool_adjust")
        ToolButton(Icons.Filled.RotateRight, "Rotate", { vm.rotate() }, tag = "tool_rotate")
        ToolButton(Icons.Filled.Flip, "Flip", { vm.flipH() }, tag = "tool_flip")
        ToolButton(if (c.fit == FitMode.FIT) Icons.Filled.FitScreen else Icons.Filled.Crop, if (c.fit == FitMode.FIT) "Fit" else "Fill", { vm.toggleFit() }, tag = "tool_fit")
        ToolButton(Icons.Filled.ZoomIn, "Zoom", { vm.setZoom(if (c.zoom >= 2.5f) 1f else c.zoom + 0.5f) }, tag = "tool_zoom")
        ToolButton(Icons.Filled.ChevronLeft, "Move left", { vm.moveClip(c.id, idx - 1) }, enabled = idx > 0, tag = "tool_move_left")
        ToolButton(Icons.Filled.ChevronRight, "Move right", { vm.moveClip(c.id, idx + 1) }, enabled = idx < vm.project.clips.lastIndex, tag = "tool_move_right")
        if (!c.isImage) ToolButton(Icons.Filled.GraphicEq, "Extract audio", { vm.extractAudio() }, tag = "tool_extract")
        ToolButton(Icons.Filled.ContentCopy, "Duplicate", { vm.duplicateSelected() }, tag = "tool_duplicate")
        ToolButton(Icons.Filled.Delete, "Delete", { vm.deleteSelected() }, tint = EditsColors.Red, tag = "tool_delete")
    }
}

@UnstableApi
@Composable
private fun TextToolbar(vm: EditorViewModel) {
    ToolRow(Modifier.testTag("toolbar_text")) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { vm.select(Selection.None) }, tag = "tool_back")
        ToolButton(Icons.Filled.Edit, "Edit", { vm.openPanel(Panel.TEXT_EDIT) }, tag = "tool_text_edit")
        ToolButton(Icons.Filled.Palette, "Style", { vm.openPanel(Panel.TEXT_STYLE) }, tag = "tool_text_style")
        ToolButton(Icons.Filled.ContentCut, "Split", { vm.split() }, tag = "tool_split")
        ToolButton(Icons.Filled.ContentCopy, "Duplicate", { vm.duplicateSelected() }, tag = "tool_duplicate")
        ToolButton(Icons.Filled.Delete, "Delete", { vm.deleteSelected() }, tint = EditsColors.Red, tag = "tool_delete")
    }
}

@UnstableApi
@Composable
private fun StickerToolbar(vm: EditorViewModel) {
    ToolRow(Modifier.testTag("toolbar_sticker")) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { vm.select(Selection.None) }, tag = "tool_back")
        ToolButton(Icons.Filled.ContentCopy, "Duplicate", { vm.duplicateSelected() }, tag = "tool_duplicate")
        ToolButton(Icons.Filled.Delete, "Delete", { vm.deleteSelected() }, tint = EditsColors.Red, tag = "tool_delete")
    }
}

@UnstableApi
@Composable
private fun AudioToolbar(vm: EditorViewModel) {
    ToolRow(Modifier.testTag("toolbar_audio")) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { vm.select(Selection.None) }, tag = "tool_back")
        ToolButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume", { vm.openPanel(Panel.VOLUME) }, tag = "tool_volume")
        ToolButton(Icons.Filled.Gradient, "Fade", { vm.openPanel(Panel.FADE) }, tag = "tool_fade")
        ToolButton(Icons.Filled.ContentCut, "Split", { vm.split() }, tag = "tool_split")
        ToolButton(Icons.Filled.ContentCopy, "Duplicate", { vm.duplicateSelected() }, tag = "tool_duplicate")
        ToolButton(Icons.Filled.Delete, "Delete", { vm.deleteSelected() }, tint = EditsColors.Red, tag = "tool_delete")
    }
}

// ---- clip panels ------------------------------------------------------------------------------

@UnstableApi
@Composable
private fun SpeedPanel(vm: EditorViewModel) {
    val c = vm.selectedClip ?: return
    Column(Modifier.testTag("panel_speed")) {
        if (c.isImage) {
            PanelHeader("Duration", { vm.closePanel() })
            val seconds = (c.outMs - c.inMs) / 1000f
            LabeledSlider("Show for", seconds, { v -> vm.trimSelectedClip(c.inMs, c.inMs + (v * 1000).toLong()) }, 0.5f..15f, { String.format(Locale.US, "%.1fs", it) }, tag = "slider_duration")
        } else {
            PanelHeader("Speed", { vm.closePanel() })
            LabeledSlider("Speed", c.speed, { vm.setSpeed(snapSpeed(it)) }, 0.1f..10f, { String.format(Locale.US, "%.1fx", it) }, tag = "slider_speed")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f, 8f).forEach { s ->
                    Chip("${if (s < 1 || s % 1 != 0f) s else s.toInt()}x", c.speed == s, { vm.setSpeed(s) }, tag = "speed_$s")
                }
            }
            Text("New length: ${formatDuration(c.durationMs)}", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 8.dp))
        }
    }
}

/** Slider values near common presets snap to them so 1x is easy to hit. */
private fun snapSpeed(v: Float): Float {
    val snaps = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
    val near = snaps.firstOrNull { kotlin.math.abs(it - v) < 0.06f * it.coerceAtLeast(1f) }
    return near ?: (v * 10).roundToInt() / 10f
}

@UnstableApi
@Composable
private fun VolumePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip
    val audio = vm.selectedAudio
    val volume = clip?.volume ?: audio?.volume ?: return
    Column(Modifier.testTag("panel_volume")) {
        PanelHeader("Volume", { vm.closePanel() })
        LabeledSlider("Volume", volume, { v -> if (clip != null) vm.setVolume(v) else vm.updateAudio("avol") { it.copy(volume = v.coerceIn(0f, 2f)) } }, 0f..2f, { "${(it * 100).roundToInt()}%" }, tag = "slider_volume")
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Mute", volume == 0f, { if (clip != null) vm.setVolume(0f) else vm.updateAudio { it.copy(volume = 0f) } }, tag = "vol_mute")
            Chip("100%", volume == 1f, { if (clip != null) vm.setVolume(1f) else vm.updateAudio { it.copy(volume = 1f) } }, tag = "vol_100")
            Chip("Boost 150%", volume == 1.5f, { if (clip != null) vm.setVolume(1.5f) else vm.updateAudio { it.copy(volume = 1.5f) } }, tag = "vol_150")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@UnstableApi
@Composable
private fun FadePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip
    val audio = vm.selectedAudio
    val inMs = clip?.fadeInMs ?: audio?.fadeInMs ?: return
    val outMs = clip?.fadeOutMs ?: audio?.fadeOutMs ?: return
    val dur = clip?.durationMs ?: audio?.durationMs ?: return
    val maxFade = (dur / 2f).coerceAtMost(3000f).coerceAtLeast(100f)
    fun apply(i: Long, o: Long) {
        if (clip != null) vm.setFades(i, o)
        else vm.updateAudio("afade") { it.copy(fadeInMs = i.coerceIn(0, it.durationMs / 2), fadeOutMs = o.coerceIn(0, it.durationMs / 2)) }
    }
    Column(Modifier.testTag("panel_fade")) {
        PanelHeader(if (clip != null) "Fade in / out" else "Fade audio", { vm.closePanel() })
        LabeledSlider("Fade in", inMs.toFloat(), { apply(it.toLong(), outMs) }, 0f..maxFade, { String.format(Locale.US, "%.1fs", it / 1000f) }, tag = "slider_fade_in")
        LabeledSlider("Fade out", outMs.toFloat(), { apply(inMs, it.toLong()) }, 0f..maxFade, { String.format(Locale.US, "%.1fs", it / 1000f) }, tag = "slider_fade_out")
        if (clip != null) Text("Video fades to and from black; the clip's sound fades too.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
    }
}

@UnstableApi
@Composable
private fun FilterPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: vm.project.clips.firstOrNull() ?: return
    var all by remember { mutableStateOf(false) }
    val frame by produceState<android.graphics.Bitmap?>(null, clip.path, clip.inMs) {
        // a little way into the clip so fades / black first frames don't make every swatch dark
        val at = clip.inMs + minOf(600L, (clip.outMs - clip.inMs) / 3)
        value = Thumbs.frame(clip.path, clip.isImage, at, 160)
    }
    // Shown until (or instead of) the real frame so the swatches always preview the colour grade.
    val fallback = remember { swatchGradient() }
    Column(Modifier.testTag("panel_filter")) {
        PanelHeader("Filters", { vm.closePanel() }, trailing = {
            Text("All clips", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Switch(checked = all, onCheckedChange = { all = it }, modifier = Modifier.padding(horizontal = 4.dp).testTag("filter_all").height(24.dp))
        })
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(FilterPresets.all) { f ->
                val selected = clip.filter == f.id
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (vm.selectedClip == null) vm.selectClipAtPlayhead(); vm.setFilter(f.id, all) }.testTag("filter_${f.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(60.dp, 72.dp).clip(RoundedCornerShape(10.dp)).background(EditsColors.Surface3).then(if (selected) Modifier.border(2.dp, EditsColors.Blue, RoundedCornerShape(10.dp)) else Modifier)) {
                        Image(
                            (frame ?: fallback).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(ColorMath.toAndroidColorMatrix(f.matrix))),
                        )
                    }
                    Text(f.label, color = if (selected) Color.White else EditsColors.TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (clip.filter != "none") {
            LabeledSlider("Intensity", clip.filterIntensity, { vm.setFilterIntensity(it, all) }, 0f..1f, { "${(it * 100).roundToInt()}%" }, tag = "slider_intensity")
        } else Spacer(Modifier.height(12.dp))
    }
}

@UnstableApi
@Composable
private fun AdjustPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: vm.project.clips.firstOrNull() ?: return
    var all by remember { mutableStateOf(false) }
    val a = clip.adjust
    data class Row(val label: String, val get: (Adjust) -> Float, val set: (Adjust, Float) -> Adjust)
    val rows = listOf(
        Row("Brightness", { it.brightness }) { x, v -> x.copy(brightness = v) },
        Row("Contrast", { it.contrast }) { x, v -> x.copy(contrast = v) },
        Row("Saturation", { it.saturation }) { x, v -> x.copy(saturation = v) },
        Row("Exposure", { it.exposure }) { x, v -> x.copy(exposure = v) },
        Row("Warmth", { it.warmth }) { x, v -> x.copy(warmth = v) },
        Row("Tint", { it.tint }) { x, v -> x.copy(tint = v) },
    )
    Column(Modifier.testTag("panel_adjust")) {
        PanelHeader("Adjust", { vm.closePanel() }, trailing = {
            TextButton(onClick = { vm.setAdjust(Adjust(), all) }, enabled = !a.isNeutral, modifier = Modifier.testTag("adjust_reset")) { Text("Reset", color = if (a.isNeutral) EditsColors.TextDim else EditsColors.Blue) }
            Text("All", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Switch(checked = all, onCheckedChange = { all = it }, modifier = Modifier.padding(horizontal = 4.dp).testTag("adjust_all").height(24.dp))
        })
        Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { r ->
                LabeledSlider(r.label, r.get(a), { v -> if (vm.selectedClip == null) vm.selectClipAtPlayhead(); vm.setAdjust(r.set(clip.adjust, (v * 100).roundToInt() / 100f), all) }, -1f..1f, { "${(it * 100).roundToInt()}" }, tag = "slider_${r.label}")
            }
        }
    }
}

@UnstableApi
@Composable
private fun CanvasPanel(vm: EditorViewModel) {
    Column(Modifier.testTag("panel_canvas")) {
        PanelHeader("Canvas", { vm.closePanel() })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AspectRatio.entries.forEach { ar ->
                val sel = vm.project.aspect == ar
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.setCanvas(ar) }.testTag("aspect_${ar.label}").padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val w = if (ar.ratio >= 1f) 44.dp else (44 * ar.ratio).dp
                    val h = if (ar.ratio >= 1f) (44 / ar.ratio).dp else 44.dp
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(w, h).clip(RoundedCornerShape(4.dp)).background(if (sel) EditsColors.Blue else EditsColors.Surface3))
                    }
                    Text(ar.label, color = if (sel) Color.White else EditsColors.TextDim, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("Clips are fitted to the canvas; use Fill on a clip to crop it instead of leaving bars.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
    }
}

// ---- audio panels -----------------------------------------------------------------------------

@UnstableApi
@Composable
private fun AudioMenuPanel(vm: EditorViewModel, a: EditorActions) {
    Column(Modifier.testTag("panel_audio")) {
        PanelHeader("Audio", { vm.closePanel() })
        if (vm.recording) {
            RecordingBar(vm)
        } else {
            ToolRow {
                ToolButton(Icons.Filled.LibraryMusic, "Sounds", { vm.openPanel(Panel.SOUNDS) }, tag = "audio_sounds")
                ToolButton(Icons.Filled.FolderOpen, "Your audio", a.pickAudio, tag = "audio_import")
                ToolButton(Icons.Filled.Mic, "Voiceover", a.startVoice, tag = "audio_voice")
                ToolButton(Icons.Filled.RecordVoiceOver, "Speech", { vm.openPanel(Panel.TTS) }, tag = "audio_tts")
                ToolButton(Icons.Filled.GraphicEq, "Extract", { vm.selectClipAtPlayhead(); vm.extractAudio() }, tag = "audio_extract")
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@UnstableApi
@Composable
private fun RecordingBar(vm: EditorViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("recording_bar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(EditsColors.Red))
        Text("Recording… ${formatTime(vm.playheadMs)}", color = Color.White, modifier = Modifier.weight(1f))
        Box(Modifier.width(80.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(EditsColors.Surface3)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(vm.recordLevel.coerceIn(0.03f, 1f)).background(EditsColors.Green))
        }
        PrimaryButton("Stop", { vm.stopRecording() }, tag = "record_stop")
    }
}

@UnstableApi
@Composable
private fun SoundsPanel(vm: EditorViewModel) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(SoundCategory.MUSIC) }
    var playingId by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { runCatching { player[0]?.release() }; player[0] = null } }

    fun stop() { runCatching { player[0]?.stop() }; runCatching { player[0]?.release() }; player[0] = null; playingId = null }
    suspend fun preview(item: com.myedits.app.audio.SoundItem) {
        if (playingId == item.id) { stop(); return }
        stop()
        val f = withContext(Dispatchers.IO) { SoundLibrary.file(File(ctx.filesDir, "sounds"), item.id) }
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(f.path); mp.prepare(); mp.setOnCompletionListener { stop() }; mp.start()
            player[0] = mp; playingId = item.id
        }
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.testTag("panel_sounds")) {
        PanelHeader("Sounds", { stop(); vm.openPanel(Panel.AUDIO) })
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundCategory.entries.forEach { c -> Chip(c.label, tab == c, { stop(); tab = c }, tag = "sound_tab_${c.name}") }
        }
        LazyRow(Modifier.padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(SoundLibrary.items.filter { it.category == tab }) { item ->
                Column(
                    Modifier.width(124.dp).clip(RoundedCornerShape(12.dp)).background(EditsColors.Surface2).padding(10.dp).testTag("sound_${item.id}"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.emoji, fontSize = 20.sp)
                        Text(formatDuration(item.durationMs), color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(item.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        IconButton({ scope.launch { preview(item) } }, Modifier.size(34.dp).background(EditsColors.Surface3, CircleShape).testTag("sound_play_${item.id}")) {
                            Icon(if (playingId == item.id) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Preview", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton({ stop(); vm.addSound(item.id); vm.closePanel() }, Modifier.size(34.dp).background(EditsColors.Blue, CircleShape).testTag("sound_add_${item.id}")) {
                            Icon(Icons.Filled.Add, "Add", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun TtsPanel(vm: EditorViewModel) {
    var text by remember { mutableStateOf("") }
    var pitch by remember { mutableFloatStateOf(1f) }
    var rate by remember { mutableFloatStateOf(1f) }
    Column(Modifier.testTag("panel_tts")) {
        PanelHeader("Text to speech", { vm.openPanel(Panel.AUDIO) })
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(400) }, placeholder = { Text("Type what should be said…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("tts_input"), maxLines = 3,
            colors = fieldColors(),
        )
        LabeledSlider("Pitch", pitch, { pitch = it }, 0.5f..2f, { String.format(Locale.US, "%.1f", it) }, tag = "slider_pitch")
        LabeledSlider("Speed", rate, { rate = it }, 0.5f..2f, { String.format(Locale.US, "%.1fx", it) }, tag = "slider_rate")
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            PrimaryButton("Add speech", { vm.addSpeech(text, pitch, rate) }, enabled = text.isNotBlank(), tag = "tts_add")
        }
    }
}

// ---- text panels ------------------------------------------------------------------------------

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = EditsColors.Blue, unfocusedBorderColor = EditsColors.Outline,
    cursorColor = EditsColors.Blue, focusedPlaceholderColor = EditsColors.TextDim, unfocusedPlaceholderColor = EditsColors.TextDim,
)

@UnstableApi
@Composable
private fun TextEditPanel(vm: EditorViewModel) {
    val t = vm.selectedText ?: return
    val focus = remember { FocusRequester() }
    var value by remember(t.id) { mutableStateOf(t.text) }
    LaunchedEffect(t.id) { runCatching { focus.requestFocus() } }
    Column(Modifier.testTag("panel_text_edit")) {
        PanelHeader("Edit text", { vm.closePanel() })
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.take(200); vm.updateText("text") { x -> x.copy(text = it.take(200)) } },
            placeholder = { Text("Type something…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus).testTag("text_input"),
            maxLines = 4, colors = fieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Style", { vm.openPanel(Panel.TEXT_STYLE) }, Modifier.weight(1f), tag = "text_to_style")
            PrimaryButton("Done", { vm.closePanel() }, Modifier.weight(1f), tag = "text_done")
        }
    }
}

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF32D74B, 0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF2D87, 0xFF8E8E93)

@UnstableApi
@Composable
private fun TextStylePanel(vm: EditorViewModel) {
    val t = vm.selectedText ?: return
    Column(Modifier.testTag("panel_text_style")) {
        PanelHeader("Text style", { vm.closePanel() })
        Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontStyleId.entries.forEach { f -> Chip(f.label, t.font == f, { vm.updateText { x -> x.copy(font = f) } }, tag = "font_${f.name}") }
            }
            ColorRow("Text", t.color, "color") { c -> vm.updateText { it.copy(color = c) } }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Background", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall)
                Chip("None", t.bg == TextBg.NONE, { vm.updateText { it.copy(bg = TextBg.NONE) } }, tag = "bg_NONE")
                Chip("Solid", t.bg == TextBg.SOLID, { vm.updateText { it.copy(bg = TextBg.SOLID, bgColor = if (it.bgColor == 0xFF000000 && it.color == 0xFF000000) 0xFFFFFFFF else it.bgColor) } }, tag = "bg_SOLID")
                Chip("Rounded", t.bg == TextBg.ROUNDED, { vm.updateText { it.copy(bg = TextBg.ROUNDED) } }, tag = "bg_ROUNDED")
            }
            if (t.bg != TextBg.NONE) ColorRow("Fill", t.bgColor, "bgcolor") { c -> vm.updateText { it.copy(bgColor = c) } }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.updateText { it.copy(align = TextAlign.LEFT) } }, Modifier.testTag("align_LEFT")) { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Left", tint = if (t.align == TextAlign.LEFT) EditsColors.Blue else Color.White) }
                IconButton({ vm.updateText { it.copy(align = TextAlign.CENTER) } }, Modifier.testTag("align_CENTER")) { Icon(Icons.Filled.FormatAlignCenter, "Centre", tint = if (t.align == TextAlign.CENTER) EditsColors.Blue else Color.White) }
                IconButton({ vm.updateText { it.copy(align = TextAlign.RIGHT) } }, Modifier.testTag("align_RIGHT")) { Icon(Icons.AutoMirrored.Filled.FormatAlignRight, "Right", tint = if (t.align == TextAlign.RIGHT) EditsColors.Blue else Color.White) }
                Chip("Outline", t.outline, { vm.updateText { it.copy(outline = !it.outline) } }, tag = "text_outline")
                Chip("Shadow", t.shadow, { vm.updateText { it.copy(shadow = !it.shadow) } }, tag = "text_shadow")
            }
            LabeledSlider("Size", t.scale, { vm.updateText("tsize") { x -> x.copy(scale = it) } }, 0.4f..4f, { String.format(Locale.US, "%.1fx", it) }, tag = "slider_text_size")
        }
    }
}

@Composable
private fun ColorRow(label: String, selected: Long, key: String, onPick: (Long) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        TEXT_COLORS.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (selected == c) 3.dp else 1.dp, if (selected == c) EditsColors.Blue else EditsColors.Outline, CircleShape)
                    .clickable { onPick(c) }.testTag("${key}_${c.toString(16)}"),
            )
        }
    }
}

// ---- stickers ---------------------------------------------------------------------------------

private val EMOJI_SETS = linkedMapOf(
    "Smileys" to "😀😂🥹😍🥰😎🤩🥳😜🤪😭😱😴🤔🙄😇🤯🥶🔥✨💯",
    "Hearts" to "❤️🧡💛💚💙💜🖤🤍💖💘💝💕💞💔❣️💋🌹⭐🌟💫⚡",
    "Nature" to "🐶🐱🦊🐼🦁🐸🐵🦄🐝🦋🌸🌻🌈☀️🌙⛅❄️🌊🍀🌴",
    "Food" to "🍕🍔🍟🌮🍩🍪🍰🍫🍿🍓🍉🍑🥑☕🧋🍹🍺🍷🥂🎂",
    "Fun" to "🎉🎊🎈🎁🏆🎬🎤🎧🎮⚽🏀🎯🎨📸🎵🎶🚀✈️🏖️🌍",
    "Hands" to "👍👎👏🙌🤝🙏💪🤞✌️🤟👋🤙👀👑💎💰📌✅❌❓",
)

@UnstableApi
@Composable
private fun StickersPanel(vm: EditorViewModel, a: EditorActions) {
    var tab by remember { mutableStateOf(EMOJI_SETS.keys.first()) }
    val emojis = remember(tab) { splitEmoji(EMOJI_SETS.getValue(tab)) }
    Column(Modifier.testTag("panel_stickers")) {
        PanelHeader("Stickers", { vm.closePanel() })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EMOJI_SETS.keys.forEach { k -> Chip(k, tab == k, { tab = k }, tag = "emoji_tab_$k") }
            Chip("📷 Photo", false, a.pickOverlay, tag = "sticker_photo")
        }
        LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.heightIn(max = 170.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
            items(emojis) { e ->
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.addSticker(e); vm.closePanel() }.testTag("emoji_$e"), contentAlignment = Alignment.Center) {
                    Text(e, fontSize = 28.sp)
                }
            }
        }
    }
}

/** Splits a string into user-perceived emoji (keeps ZWJ / variation-selector sequences together). */
internal fun splitEmoji(s: String): List<String> {
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) { out += s.substring(start, end); start = end; end = it.next() }
    return out
}


/** A small colourful gradient used to preview filters when no video frame is available yet. */
private fun swatchGradient(): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(60, 72, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 60f, 72f, intArrayOf(0xFFF4A261.toInt(), 0xFFE76F51.toInt(), 0xFF2A9D8F.toInt(), 0xFF264653.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, 60f, 72f, p)
    return bmp
}
