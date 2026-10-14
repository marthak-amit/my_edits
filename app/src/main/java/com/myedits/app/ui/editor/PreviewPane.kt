package com.myedits.app.ui.editor

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.myedits.app.data.*
import com.myedits.app.engine.LayerRenderer
import com.myedits.app.ui.theme.EditsColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything the interactive layer needs to place one text/sticker on the preview canvas. */
private data class LayerVisual(
    val sel: Selection,
    val bitmap: Bitmap,
    val x: Float, val y: Float, val rotation: Float,
)

@UnstableApi
@Composable
fun PreviewPane(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val project = vm.project
    val playhead = vm.playheadMs
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val ratio = project.aspect.ratio
        val (w, h) = if (maxW / maxH > ratio) (maxH * ratio) to maxH else maxW to (maxW / ratio)
        val wDp = with(density) { w.toDp() }
        val hDp = with(density) { h.toDp() }

        Box(Modifier.size(wDp, hDp).clipToBounds().background(Color(0xFF0B0B0D)).testTag("preview")) {
            if (project.clips.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎞️", style = MaterialTheme.typography.headlineLarge)
                    Text("Tap + to add videos or photos", color = EditsColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).also { vm.preview.attach(it) } },
                    onRelease = { vm.preview.detach(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            LayerOverlay(vm, project, playhead, w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
        }
    }
}

@UnstableApi
@Composable
private fun LayerOverlay(vm: EditorViewModel, project: Project, playhead: Long, w: Int, h: Int) {
    val selection = vm.selection
    // visible layers at the playhead; the last drawn is topmost
    val visibleStickers = project.stickers.filter { playhead >= it.startMs && playhead < it.endMs }
    val visibleTexts = project.texts.filter { playhead >= it.startMs && playhead < it.endMs }

    val visuals = ArrayList<LayerVisual>()
    visibleStickers.forEach { s ->
        val bmp = remember(s.kind, s.emoji, s.path, s.scale, w) { LayerRenderer.renderSticker(s, w, h) }
        visuals += LayerVisual(Selection.OfSticker(s.id), bmp, s.x, s.y, s.rotation)
    }
    visibleTexts.forEach { t ->
        val bmp = remember(t.text, t.color, t.bgColor, t.bg, t.font, t.align, t.outline, t.shadow, t.scale, w, h) { LayerRenderer.renderText(t, w, h) }
        visuals += LayerVisual(Selection.OfText(t.id), bmp, t.x, t.y, t.rotation)
    }

    val curVisuals by rememberUpdatedState(visuals.toList())
    val curSelection by rememberUpdatedState(selection)
    val curProject by rememberUpdatedState(project)

    Box(
        Modifier
            .fillMaxSize()
            .testTag("layer_overlay")
            .pointerInput(w, h) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var totalPan = Offset.Zero
                    val startSel = curSelection
                    // If the gesture starts on a layer, that layer becomes the transform target.
                    val hitSel = hitTest(curVisuals, down.position, w, h)
                    var target: Selection = if (startSel != Selection.None && (startSel is Selection.OfText || startSel is Selection.OfSticker)) startSel else Selection.None
                    if (hitSel != null && target == Selection.None) target = hitSel
                    var event = awaitPointerEvent(PointerEventPass.Main)
                    while (event.changes.any { it.pressed }) {
                        val pan = event.calculatePan(); val zoom = event.calculateZoom(); val rot = event.calculateRotation()
                        totalPan += pan
                        if (!moved && (totalPan.getDistance() > viewConfiguration.touchSlop)) moved = true
                        if (moved && target != Selection.None) {
                            applyTransform(vm, curProject, target, pan, zoom, rot, w, h)
                            event.changes.forEach { it.consume() }
                        }
                        event = awaitPointerEvent(PointerEventPass.Main)
                    }
                    if (!moved) {
                        when {
                            hitSel != null -> vm.select(hitSel)
                            startSel != Selection.None -> vm.select(Selection.None)
                            else -> vm.togglePlay()
                        }
                    } else if (hitSel != null && startSel != hitSel && target == hitSel) vm.select(hitSel)
                }
            },
    ) {
        visuals.forEach { v ->
            val selected = v.sel == selection
            val bw = v.bitmap.width; val bh = v.bitmap.height
            Box(
                Modifier
                    .offset { IntOffset((v.x * w - bw / 2f).roundToInt(), (v.y * h - bh / 2f).roundToInt()) }
                    .graphicsLayer { rotationZ = v.rotation }
                    .size(with(LocalDensity.current) { bw.toDp() }, with(LocalDensity.current) { bh.toDp() })
                    .then(if (selected) Modifier.border(1.5.dp, Color.White) else Modifier),
            ) { Image(v.bitmap.asImageBitmap(), null, Modifier.fillMaxSize()) }
        }
    }
}

private fun hitTest(visuals: List<LayerVisual>, p: Offset, w: Int, h: Int): Selection? {
    for (v in visuals.asReversed()) {
        val cx = v.x * w; val cy = v.y * h
        val dx = p.x - cx; val dy = p.y - cy
        val a = Math.toRadians(-v.rotation.toDouble())
        val lx = dx * cos(a) - dy * sin(a)
        val ly = dx * sin(a) + dy * cos(a)
        if (kotlin.math.abs(lx) <= max(v.bitmap.width / 2f, 24f) && kotlin.math.abs(ly) <= max(v.bitmap.height / 2f, 24f)) return v.sel
    }
    return null
}

@UnstableApi
private fun applyTransform(vm: EditorViewModel, project: Project, sel: Selection, pan: Offset, zoom: Float, rotDeg: Float, w: Int, h: Int) {
    when (sel) {
        is Selection.OfText -> project.texts.firstOrNull { it.id == sel.id }?.let { t ->
            vm.transformText(t.id, t.x + pan.x / w, t.y + pan.y / h, (t.scale * zoom).coerceIn(0.2f, 6f), t.rotation + rotDeg)
        }
        is Selection.OfSticker -> project.stickers.firstOrNull { it.id == sel.id }?.let { s ->
            vm.transformSticker(s.id, s.x + pan.x / w, s.y + pan.y / h, (s.scale * zoom).coerceIn(0.05f, 3f), s.rotation + rotDeg)
        }
        else -> {}
    }
}
