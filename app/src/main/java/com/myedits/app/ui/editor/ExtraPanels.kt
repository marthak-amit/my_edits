package com.myedits.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import com.myedits.app.ui.common.*
import com.myedits.app.ui.theme.EditsColors
import java.util.Locale
import kotlin.math.roundToInt

/** A big tappable option with an emoji, used for transitions and animations. */
@Composable
private fun OptionTile(emoji: String, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x330A84FF) else EditsColors.Surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) EditsColors.Blue else EditsColors.Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji, fontSize = 24.sp)
        Text(label, color = if (selected) Color.White else EditsColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ApplyAllChip(all: Boolean, onToggle: () -> Unit, tag: String = "apply_all") {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Chip("Apply to all clips", all, onToggle, tag = tag) }
}

@UnstableApi
@Composable
internal fun TransitionPanel(vm: EditorViewModel) {
    val c = vm.selectedClip ?: return
    var all by remember { mutableStateOf(false) }
    val isLast = vm.project.clips.lastOrNull()?.id == c.id
    val maxMs = ProjectOps.maxTransitionMs(vm.project, c.id)
    Column(Modifier.testTag("panel_transition")) {
        PanelHeader("Transition", { vm.closePanel() })
        TileRow {
            Transition.entries.forEach { t -> OptionTile(t.emoji, t.label, c.transition == t, "transition_${t.name}") { vm.setTransition(t, all) } }
        }
        if (c.transition != Transition.NONE && !isLast) {
            LabeledSlider("Length", c.transitionMs.toFloat().coerceIn(ProjectOps.MIN_TRANSITION_MS.toFloat(), maxMs.toFloat()), { vm.setTransitionDuration(it.toLong()) },
                ProjectOps.MIN_TRANSITION_MS.toFloat()..maxMs.toFloat().coerceAtLeast(ProjectOps.MIN_TRANSITION_MS + 1f), { String.format(Locale.US, "%.2fs", it / 1000f) }, tag = "slider_transition_ms")
        }
        ApplyAllChip(all, { all = !all })
        Text(
            if (isLast) "A transition plays between two clips. This is the last clip, so pick an earlier one." else "Plays where this clip ends and the next one begins.",
            color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

@UnstableApi
@Composable
internal fun AnimatePanel(vm: EditorViewModel) {
    val c = vm.selectedClip ?: return
    var all by remember { mutableStateOf(false) }
    Column(Modifier.testTag("panel_animate")) {
        PanelHeader("Animate clip", { vm.closePanel() })
        TileRow {
            ClipAnim.entries.forEach { a -> OptionTile(a.emoji, a.label, c.animation == a, "anim_${a.name}") { vm.setClipAnim(a, all) } }
        }
        ApplyAllChip(all, { all = !all })
        Text("Slow movement across the whole clip — great for photos.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
}

@UnstableApi
@Composable
internal fun EffectsPanel(vm: EditorViewModel) {
    val c = vm.selectedClip ?: return
    var all by remember { mutableStateOf(false) }
    Column(Modifier.testTag("panel_effects")) {
        PanelHeader("Effects", { vm.closePanel() })
        LabeledSlider("Blur", c.blur, { vm.setBlur(it, all) }, 0f..1f, { "${(it * 100).roundToInt()}%" }, tag = "slider_blur")
        LabeledSlider("Vignette", c.vignette, { vm.setVignette(it, all) }, 0f..1f, { "${(it * 100).roundToInt()}%" }, tag = "slider_vignette")
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Cinema bars", c.bars, { vm.setBars(!c.bars, all) }, tag = "fx_bars")
            Chip("Apply to all clips", all, { all = !all }, tag = "apply_all")
            Chip("Reset", false, { vm.setBlur(0f, all); vm.setVignette(0f, all); vm.setBars(false, all) }, tag = "fx_reset")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@UnstableApi
@Composable
internal fun PositionPanel(vm: EditorViewModel) {
    val c = vm.selectedClip ?: return
    Column(Modifier.testTag("panel_position")) {
        PanelHeader("Crop & position", { vm.closePanel() })
        LabeledSlider("Zoom", c.zoom, { vm.setZoom(it) }, 1f..3f, { String.format(Locale.US, "%.2fx", it) }, tag = "slider_zoom")
        if (c.zoom > 1f) {
            LabeledSlider("Move left / right", c.panX, { vm.setPan(it, c.panY) }, -1f..1f, { "${(it * 100).roundToInt()}" }, tag = "slider_pan_x")
            LabeledSlider("Move up / down", c.panY, { vm.setPan(c.panX, it) }, -1f..1f, { "${(it * 100).roundToInt()}" }, tag = "slider_pan_y")
        } else {
            Text("Zoom in to move the picture around inside the frame.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Rotate 90°", c.rotation != 0, { vm.rotate() }, tag = "pos_rotate")
            Chip("Flip ↔", c.flipH, { vm.flipH() }, tag = "pos_flip_h")
            Chip("Flip ↕", c.flipV, { vm.flipV() }, tag = "pos_flip_v")
            Chip(if (c.fit == FitMode.FIT) "Fit" else "Fill", c.fit == FitMode.FILL, { vm.toggleFit() }, tag = "pos_fit")
            Chip("Reset", false, { vm.resetPosition() }, tag = "pos_reset")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@UnstableApi
@Composable
internal fun LayerAnimPanel(vm: EditorViewModel) {
    val text = vm.selectedText
    val sticker = vm.selectedSticker
    val anim = text?.anim ?: sticker?.anim ?: return
    val opacity = text?.opacity ?: sticker?.opacity ?: 1f
    Column(Modifier.testTag("panel_layer_anim")) {
        PanelHeader("Animation", { vm.closePanel() })
        TileRow {
            LayerAnim.entries.forEach { a ->
                OptionTile(when (a) { LayerAnim.NONE -> "⏹"; LayerAnim.FADE -> "🌫"; LayerAnim.POP -> "💥"; LayerAnim.SLIDE_UP -> "⬆️"; LayerAnim.SLIDE_LEFT -> "➡️" }, a.label, anim == a, "layer_anim_${a.name}") { vm.setLayerAnim(a) }
            }
        }
        LabeledSlider("Opacity", opacity, { vm.setLayerOpacity(it) }, 0.1f..1f, { "${(it * 100).roundToInt()}%" }, tag = "slider_opacity")
        Text("The animation plays as it appears and again as it leaves.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
}

@UnstableApi
@Composable
internal fun CaptionsPanel(vm: EditorViewModel) {
    var script by remember { mutableStateOf("") }
    Column(Modifier.testTag("panel_captions")) {
        PanelHeader("Captions from script", { vm.closePanel() })
        Text("Type one caption per line. They are spread evenly over your video — drag them on the timeline to fine-tune.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(
            script, { script = it.take(2000) }, placeholder = { Text("First line\nSecond line\n…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 96.dp).testTag("captions_input"),
            minLines = 3, maxLines = 6, colors = fieldColors(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            PrimaryButton("Add captions", { vm.addCaptions(script); vm.closePanel() }, Modifier.fillMaxWidth(), enabled = script.isNotBlank(), tag = "captions_add")
        }
    }
}

private data class VoicePreset(val key: String, val emoji: String, val label: String, val pitch: Float)

private val VOICES = listOf(
    VoicePreset("normal", "🎙", "Normal", 1f), VoicePreset("chipmunk", "🐿", "Chipmunk", 1.45f), VoicePreset("squeaky", "🐭", "Squeaky", 1.8f),
    VoicePreset("deep", "🐻", "Deep", 0.75f), VoicePreset("giant", "🦖", "Giant", 0.58f),
)

@UnstableApi
@Composable
internal fun VoicePanel(vm: EditorViewModel) {
    val a = vm.selectedAudio ?: return
    Column(Modifier.testTag("panel_voice")) {
        PanelHeader("Voice effects", { vm.closePanel() })
        TileRow {
            VOICES.forEach { v -> OptionTile(v.emoji, v.label, kotlin.math.abs(a.pitch - v.pitch) < 0.02f, "voice_${v.key}") { vm.updateAudio { it.copy(pitch = v.pitch) } } }
        }
        LabeledSlider("Pitch", a.pitch, { vm.updateAudio("pitch") { x -> x.copy(pitch = it) } }, 0.5f..2f, { String.format(Locale.US, "%.2fx", it) }, tag = "slider_pitch")
        Text("Changes the pitch of this track without changing its speed.", color = EditsColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
    }
}
