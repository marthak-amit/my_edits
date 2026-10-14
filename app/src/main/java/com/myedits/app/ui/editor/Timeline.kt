package com.myedits.app.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.myedits.app.data.*
import com.myedits.app.engine.ProjectOps
import com.myedits.app.ui.theme.EditsColors
import com.myedits.app.util.Thumbs
import com.myedits.app.util.Waveforms
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_PX_PER_MS = 0.012f
private const val MAX_PX_PER_MS = 0.9f

/** One bar on a layer lane (text, sticker or audio). */
private data class LaneItem(
    val sel: Selection,
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val color: Color,
    val audio: AudioTrack? = null,
)

/** In-flight drag state, applied on top of the project until the gesture ends. */
private sealed interface Draft {
    data class Clip(val id: String, val inMs: Long, val outMs: Long) : Draft
    data class Layer(val sel: Selection, val startMs: Long, val durationMs: Long) : Draft
    data class Audio(val id: String, val inMs: Long, val outMs: Long, val startMs: Long) : Draft
}

private sealed interface Hit {
    data object None : Hit
    data object AddButton : Hit
    data class ClipBody(val id: String) : Hit
    data class ClipEdge(val id: String, val left: Boolean) : Hit
    data class LaneBody(val sel: Selection) : Hit
    data class LaneEdge(val sel: Selection, val left: Boolean) : Hit
}

private val RULER_H = 24.dp
private val CLIP_H = 56.dp
private val LANE_H = 28.dp
private val ROW_GAP = 5.dp
private val HANDLE_W = 16.dp

/** Lane packing: first-fit so overlapping bars land on different rows. */
private fun pack(items: List<LaneItem>): List<List<LaneItem>> {
    val lanes = mutableListOf<MutableList<LaneItem>>()
    items.sortedBy { it.startMs }.forEach { it0 ->
        val lane = lanes.firstOrNull { l -> l.last().endMs <= it0.startMs }
        if (lane != null) lane += it0 else lanes += mutableListOf(it0)
    }
    return lanes
}

@Composable
fun Timeline(
    project: Project,
    playheadMs: Long,
    selection: Selection,
    pxPerMs: Float,
    onZoom: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onSelect: (Selection) -> Unit,
    onTrimClip: (clipId: String, inMs: Long, outMs: Long) -> Unit,
    onLayerTiming: (Selection, startMs: Long, durationMs: Long) -> Unit,
    onTrimAudio: (id: String, inMs: Long, outMs: Long, startMs: Long) -> Unit,
    onAddClip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()

    var widthPx by remember { mutableIntStateOf(0) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    val fling = remember { Animatable(0f) }

    // latest values for the long-lived gesture coroutine
    val curProject by rememberUpdatedState(project)
    val curPlayhead by rememberUpdatedState(playheadMs)
    val curSelection by rememberUpdatedState(selection)
    val curPx by rememberUpdatedState(pxPerMs)
    val curDraft by rememberUpdatedState(draft)

    val rulerH = with(density) { RULER_H.toPx() }
    val clipH = with(density) { CLIP_H.toPx() }
    val laneH = with(density) { LANE_H.toPx() }
    val gap = with(density) { ROW_GAP.toPx() }
    val handleW = with(density) { HANDLE_W.toPx() }
    val addSize = with(density) { 52.dp.toPx() }

    // ---- geometry shared by drawing and hit testing --------------------------------------
    val total = project.durationMs
    fun draftOf(c: Clip): Clip = (draft as? Draft.Clip)?.takeIf { it.id == c.id }?.let { c.copy(inMs = it.inMs, outMs = it.outMs) } ?: c

    // While trimming, later clips shift; recompute spans with the draft applied.
    val liveSpans = remember(project.clips, draft) {
        var t = 0L
        project.clips.mapIndexed { i, c -> val d = draftOf(c); ClipSpan(i, d, t).also { t += d.durationMs } }
    }
    val liveTotal = liveSpans.lastOrNull()?.endMs ?: 0L

    val overlayItems = remember(project.texts, project.stickers, draft) {
        val d = draft as? Draft.Layer
        val texts = project.texts.map {
            val s = Selection.OfText(it.id)
            val (st, du) = if (d?.sel == s) d.startMs to d.durationMs else it.startMs to it.durationMs
            LaneItem(s, st, st + du, it.text.replace('\n', ' '), EditsColors.TextLayer)
        }
        val stickers = project.stickers.map {
            val s = Selection.OfSticker(it.id)
            val (st, du) = if (d?.sel == s) d.startMs to d.durationMs else it.startMs to it.durationMs
            LaneItem(s, st, st + du, if (it.kind == StickerKind.EMOJI) it.emoji else "Photo", EditsColors.StickerLayer)
        }
        pack(texts + stickers)
    }
    val audioItems = remember(project.audios, draft) {
        val d = draft as? Draft.Audio
        pack(project.audios.map {
            val a = if (d?.id == it.id) it.copy(inMs = d.inMs, outMs = d.outMs, startMs = d.startMs) else it
            LaneItem(Selection.OfAudio(it.id), a.startMs, a.endMs, a.label, audioColor(a.kind), a)
        })
    }
    val laneRows = overlayItems + audioItems
    val contentH = rulerH + clipH + gap + laneRows.size * (laneH + gap) + 12f
    fun xOf(ms: Long, playhead: Long = curPlayhead, px: Float = curPx) = widthPx / 2f + (ms - playhead) * px

    fun hitTest(pos: Offset): Hit {
        val y = pos.y + scrollY
        val px = curPx
        // clip row
        if (y in rulerH..(rulerH + clipH)) {
            val sel = curSelection as? Selection.OfClip
            liveSpans.forEach { s ->
                val x0 = xOf(s.startMs); val x1 = xOf(s.endMs)
                if (sel?.id == s.clip.id) {
                    if (abs(pos.x - x0) <= handleW) return Hit.ClipEdge(s.clip.id, true)
                    if (abs(pos.x - x1) <= handleW) return Hit.ClipEdge(s.clip.id, false)
                }
                if (pos.x in x0..x1) return Hit.ClipBody(s.clip.id)
            }
            val addX = xOf(liveTotal) + with(density) { 8.dp.toPx() }
            if (pos.x in addX..(addX + addSize)) return Hit.AddButton
            return Hit.None
        }
        // lanes
        laneRows.forEachIndexed { i, lane ->
            val top = rulerH + clipH + gap + i * (laneH + gap)
            if (y in top..(top + laneH)) {
                lane.forEach { item ->
                    val x0 = xOf(item.startMs); val x1 = xOf(item.endMs)
                    val selected = curSelection == item.sel
                    if (selected) {
                        if (abs(pos.x - x0) <= handleW && pos.x < (x0 + x1) / 2) return Hit.LaneEdge(item.sel, true)
                        if (abs(pos.x - x1) <= handleW && pos.x >= (x0 + x1) / 2) return Hit.LaneEdge(item.sel, false)
                    }
                    if (pos.x in x0..x1) return Hit.LaneBody(item.sel)
                }
            }
        }
        return Hit.None
    }

    val curHitTest by rememberUpdatedState<(Offset) -> Hit> { hitTest(it) }
    val curLiveSpans by rememberUpdatedState(liveSpans)
    val curLiveTotal by rememberUpdatedState(liveTotal)
    val curContentH by rememberUpdatedState(contentH)

    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { min(contentH, 230.dp.toPx()).toDp() }.coerceAtLeast(120.dp))
            .clipToBounds()
            .background(Color.Black)
            .onSizeChanged { widthPx = it.width }
            .testTag("timeline")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { fling.stop() }
                    val hit = curHitTest(down.position)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moved = false
                    var mode = 0 // 0 undecided, 1 scrub, 2 vertical scroll, 3 trim/move, 4 zoom
                    var totalDx = 0f; var totalDy = 0f
                    var startSel: Selection? = null

                    // capture state for drag modes
                    val clipHit = (hit as? Hit.ClipEdge)?.let { h -> curLiveSpans.firstOrNull { it.clip.id == h.id } }
                    val clipStartIn = clipHit?.clip?.inMs ?: 0L
                    val clipStartOut = clipHit?.clip?.outMs ?: 0L
                    val clipSpeed = clipHit?.clip?.speed ?: 1f
                    val clipIsImage = clipHit?.clip?.isImage ?: false
                    val clipSource = clipHit?.clip?.sourceDurationMs ?: 0L
                    val laneSel: Selection? = when (hit) { is Hit.LaneBody -> hit.sel; is Hit.LaneEdge -> hit.sel; else -> null }
                    val laneBodyDrag = hit is Hit.LaneBody && curSelection == hit.sel
                    val layerStart: Long
                    val layerDur: Long
                    val audioRef: AudioTrack?
                    run {
                        var s = 0L; var d = 0L; var a: AudioTrack? = null
                        when (laneSel) {
                            is Selection.OfText -> curProject.texts.firstOrNull { it.id == laneSel.id }?.let { s = it.startMs; d = it.durationMs }
                            is Selection.OfSticker -> curProject.stickers.firstOrNull { it.id == laneSel.id }?.let { s = it.startMs; d = it.durationMs }
                            is Selection.OfAudio -> curProject.audios.firstOrNull { it.id == laneSel.id }?.let { a = it; s = it.startMs; d = it.durationMs }
                            else -> {}
                        }
                        layerStart = s; layerDur = d; audioRef = a
                    }
                    val trimmable = hit is Hit.ClipEdge || hit is Hit.LaneEdge || laneBodyDrag

                    var multi = false
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (!multi) { multi = true; mode = 4 }
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) onZoom((curPx * zoom).coerceIn(MIN_PX_PER_MS, MAX_PX_PER_MS))
                            event.changes.forEach { it.consume() }
                            moved = true
                            continue
                        }
                        val c = event.changes.firstOrNull() ?: break
                        tracker.addPosition(c.uptimeMillis, c.position)
                        val dp = c.positionChange()
                        totalDx += dp.x; totalDy += dp.y
                        if (mode == 0 && (abs(totalDx) > slop || abs(totalDy) > slop)) {
                            mode = when {
                                trimmable && abs(totalDx) >= abs(totalDy) -> 3
                                abs(totalDy) > abs(totalDx) * 1.6f && curContentH > size.height -> 2
                                else -> 1
                            }
                            moved = true
                            if (mode == 1) onScrubbing(true)
                            if (mode == 3) startSel = laneSel
                        }
                        if (c.pressed) when (mode) {
                            1 -> { onSeek((curPlayhead - (dp.x / curPx)).roundToInt().toLong().coerceIn(0L, curLiveTotal)); c.consume() }
                            2 -> { scrollY = (scrollY - dp.y).coerceIn(0f, max(0f, curContentH - size.height)); c.consume() }
                            3 -> {
                                val dMs = totalDx / curPx
                                when (hit) {
                                    is Hit.ClipEdge -> {
                                        val minSrc = (ProjectOps.MIN_CLIP_MS * clipSpeed).toLong()
                                        val limit = if (clipIsImage) Long.MAX_VALUE / 4 else clipSource
                                        draft = if (hit.left) {
                                            val ni = (clipStartIn + dMs * clipSpeed).toLong().coerceIn(0L, clipStartOut - minSrc)
                                            Draft.Clip(hit.id, ni, clipStartOut)
                                        } else {
                                            val no = (clipStartOut + dMs * clipSpeed).toLong().coerceIn(clipStartIn + minSrc, limit)
                                            Draft.Clip(hit.id, clipStartIn, no)
                                        }
                                    }
                                    is Hit.LaneEdge, is Hit.LaneBody -> {
                                        val left = (hit as? Hit.LaneEdge)?.left
                                        val sel = laneSel!!
                                        val tot = curProject.durationMs
                                        if (audioRef != null) {
                                            val a = audioRef
                                            draft = when (left) {
                                                true -> {
                                                    val ni = (a.inMs + dMs).toLong().coerceIn(0L, a.outMs - ProjectOps.MIN_CLIP_MS)
                                                    Draft.Audio(a.id, ni, a.outMs, (a.startMs + (ni - a.inMs)).coerceAtLeast(0L))
                                                }
                                                false -> {
                                                    val no = (a.outMs + dMs).toLong().coerceIn(a.inMs + ProjectOps.MIN_CLIP_MS, a.sourceDurationMs)
                                                    Draft.Audio(a.id, a.inMs, no, a.startMs)
                                                }
                                                null -> Draft.Audio(a.id, a.inMs, a.outMs, (a.startMs + dMs).toLong().coerceIn(0L, (tot - 100).coerceAtLeast(0L)))
                                            }
                                        } else {
                                            val minD = ProjectOps.MIN_LAYER_MS
                                            draft = when (left) {
                                                true -> {
                                                    val ns = (layerStart + dMs).toLong().coerceIn(0L, layerStart + layerDur - minD)
                                                    Draft.Layer(sel, ns, layerStart + layerDur - ns)
                                                }
                                                false -> Draft.Layer(sel, layerStart, (layerDur + dMs).toLong().coerceIn(minD, (tot - layerStart).coerceAtLeast(minD)))
                                                null -> Draft.Layer(sel, (layerStart + dMs).toLong().coerceIn(0L, (tot - layerDur).coerceAtLeast(0L)), layerDur)
                                            }
                                        }
                                    }
                                    else -> {}
                                }
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // ---- gesture finished ---------------------------------------------------
                    when (mode) {
                        0 -> { // a tap
                            when (hit) {
                                is Hit.ClipBody -> onSelect(Selection.OfClip(hit.id))
                                is Hit.ClipEdge -> onSelect(Selection.OfClip(hit.id))
                                is Hit.LaneBody -> onSelect(hit.sel)
                                is Hit.LaneEdge -> onSelect(hit.sel)
                                Hit.AddButton -> onAddClip()
                                Hit.None -> onSelect(Selection.None)
                            }
                        }
                        1 -> {
                            val vx = tracker.calculateVelocity().x
                            onScrubbing(false)
                            if (abs(vx) > 400f) {
                                scope.launch {
                                    fling.snapTo(curPlayhead.toFloat())
                                    fling.animateDecay(-vx / curPx, exponentialDecay(frictionMultiplier = 1.6f)) {
                                        val ms = value.coerceIn(0f, curLiveTotal.toFloat())
                                        onSeek(ms.toLong())
                                        if (ms <= 0f || ms >= curLiveTotal.toFloat()) scope.launch { fling.stop() }
                                    }
                                }
                            }
                        }
                        3 -> {
                            when (val d = curDraft) {
                                is Draft.Clip -> onTrimClip(d.id, d.inMs, d.outMs)
                                is Draft.Layer -> onLayerTiming(d.sel, d.startMs, d.durationMs)
                                is Draft.Audio -> onTrimAudio(d.id, d.inMs, d.outMs, d.startMs)
                                null -> {}
                            }
                            // selecting what was dragged feels natural
                            (startSel ?: laneSel)?.let { if (curSelection != it) onSelect(it) }
                            if (hit is Hit.ClipEdge && curSelection != Selection.OfClip(hit.id)) onSelect(Selection.OfClip(hit.id))
                            draft = null
                        }
                    }
                    if (mode == 4) onScrubbing(false)
                }
            },
    ) {
        // ruler
        Canvas(Modifier.fillMaxWidth().height(RULER_H)) {
            drawRuler(measurer, playheadMs, pxPerMs, liveTotal)
        }

        Box(Modifier.fillMaxSize().offset { IntOffset(0, -scrollY.roundToInt()) }) {
            // main clips
            liveSpans.forEach { s ->
                val x0 = xOf(s.startMs, playheadMs, pxPerMs); val x1 = xOf(s.endMs, playheadMs, pxPerMs)
                if (x1 < -50 || x0 > widthPx + 50) return@forEach
                ClipStrip(
                    clip = s.clip,
                    widthPx = x1 - x0,
                    selected = selection == Selection.OfClip(s.clip.id),
                    viewportLeftPx = -x0, viewportWidthPx = widthPx.toFloat(), pxPerMs = pxPerMs,
                    modifier = Modifier.offset { IntOffset(x0.roundToInt(), rulerH.roundToInt()) },
                )
            }
            // add button
            val addX = xOf(liveTotal, playheadMs, pxPerMs) + with(density) { 8.dp.toPx() }
            if (project.clips.isNotEmpty() && addX < widthPx) {
                Box(
                    Modifier.offset { IntOffset(addX.roundToInt(), rulerH.roundToInt()) }
                        .size(52.dp, CLIP_H).clip(RoundedCornerShape(8.dp)).background(EditsColors.Surface3).testTag("timeline_add"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, "Add clip", tint = Color.White) }
            }
            // layer lanes
            laneRows.forEachIndexed { i, lane ->
                val top = rulerH + clipH + gap + i * (laneH + gap)
                lane.forEach { item ->
                    val x0 = xOf(item.startMs, playheadMs, pxPerMs); val x1 = xOf(item.endMs, playheadMs, pxPerMs)
                    if (x1 < -20 || x0 > widthPx + 20) return@forEach
                    LaneBar(
                        item, selected = selection == item.sel, widthPx = max(x1 - x0, 6f),
                        modifier = Modifier.offset { IntOffset(x0.roundToInt(), top.roundToInt()) },
                    )
                }
            }
        }

        // fixed playhead
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxHeight().width(2.dp).background(Color.White).testTag("playhead"),
        )
        Box(Modifier.align(Alignment.TopCenter).offset(y = 0.dp).size(10.dp).clip(RoundedCornerShape(50)).background(Color.White))

        if (project.clips.isEmpty()) {
            Text("Add a clip to start editing", Modifier.align(Alignment.Center), color = EditsColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun audioColor(kind: AudioKind): Color = when (kind) {
    AudioKind.VOICE -> EditsColors.AudioVoice
    AudioKind.SFX -> EditsColors.AudioSfx
    else -> EditsColors.AudioMusic
}

private fun DrawScope.drawRuler(measurer: androidx.compose.ui.text.TextMeasurer, playheadMs: Long, pxPerMs: Float, totalMs: Long) {
    val steps = longArrayOf(100, 200, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000, 120_000)
    val step = steps.firstOrNull { it * pxPerMs >= 64f } ?: steps.last()
    val minor = step / 5
    val cx = size.width / 2f
    val firstMs = ((playheadMs - cx / pxPerMs) / minor).toLong() * minor - minor
    val lastMs = playheadMs + (size.width - cx) / pxPerMs
    var t = max(0L, firstMs)
    val style = TextStyle(color = Color(0xFF8E8E96), fontSize = 10.sp)
    while (t <= lastMs) {
        val x = cx + (t - playheadMs) * pxPerMs
        val major = t % step == 0L
        drawLine(Color(0xFF55555C), Offset(x, size.height - if (major) 9f else 4f), Offset(x, size.height), strokeWidth = 1f)
        if (major) {
            val label = formatRuler(t)
            val layout = measurer.measure(label, style)
            drawText(layout, topLeft = Offset(x + 4f, 1f))
        }
        t += minor
    }
    // shade beyond the end of the project
    val endX = cx + (totalMs - playheadMs) * pxPerMs
    if (endX < size.width) drawRect(Color(0x33FFFFFF), Offset(max(0f, endX), size.height - 2f), Size(size.width - max(0f, endX), 2f))
}

private fun formatRuler(ms: Long): String {
    val s = ms / 1000
    return if (ms % 1000 == 0L) "${s / 60}:${(s % 60).toString().padStart(2, '0')}" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}.${(ms % 1000) / 100}"
}

@Composable
private fun ClipStrip(
    clip: Clip,
    widthPx: Float,
    selected: Boolean,
    viewportLeftPx: Float,
    viewportWidthPx: Float,
    pxPerMs: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val heightPx = with(density) { CLIP_H.toPx() }
    val thumbH = (heightPx * 0.9f).toInt().coerceAtLeast(24)
    val aspect = if (clip.height > 0) clip.width.toFloat() / clip.height else 1f
    val thumbWPx = max(heightPx * aspect.coerceIn(0.5f, 2f), 24f)
    val count = ceil(widthPx / thumbWPx).toInt().coerceAtLeast(1)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .width(with(density) { max(widthPx, 4f).toDp() })
            .height(CLIP_H)
            .clip(shape)
            .background(EditsColors.ClipBlue)
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .testTag("clip_${clip.id}"),
    ) {
        // only realise thumbnails that are on screen
        val first = max(0, ((viewportLeftPx) / thumbWPx).toInt() - 1)
        val last = min(count - 1, ((viewportLeftPx + viewportWidthPx) / thumbWPx).toInt() + 1)
        for (i in first..last) {
            val tMs = clip.inMs + ((i * thumbWPx + thumbWPx / 2f) / pxPerMs * clip.speed).toLong()
            val bmp by produceState<android.graphics.Bitmap?>(Thumbs.cached(clip.path, tMs.coerceIn(0, clip.sourceDurationMs), thumbH), clip.path, tMs / 250, thumbH) {
                value = Thumbs.frame(clip.path, clip.isImage, tMs.coerceIn(0, max(0, clip.sourceDurationMs - 1)), thumbH) ?: value
            }
            bmp?.let {
                Image(
                    it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset { IntOffset((i * thumbWPx).roundToInt(), 0) }.size(with(density) { thumbWPx.toDp() }, CLIP_H),
                )
            }
        }
        // badges
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (clip.speed != 1f) Badge("${trimSpeed(clip.speed)}x")
            if (clip.volume == 0f && !clip.isImage) Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(2.dp)) { Icon(Icons.Filled.VolumeOff, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
            if (clip.filter != "none") Badge("FX")
        }
        if (selected) {
            Handle(Modifier.align(Alignment.CenterStart))
            Handle(Modifier.align(Alignment.CenterEnd))
        }
    }
}

private fun trimSpeed(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() else String.format(java.util.Locale.US, "%.1f", s)

@Composable
private fun Badge(text: String) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, color = Color.White, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun Handle(modifier: Modifier) {
    Box(modifier.width(14.dp).fillMaxHeight().background(Color.White, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        Box(Modifier.width(2.dp).height(18.dp).background(Color(0xFF555555), RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun LaneBar(item: LaneItem, selected: Boolean, widthPx: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .width(with(density) { widthPx.toDp() })
            .height(LANE_H)
            .clip(shape)
            .background(item.color.copy(alpha = if (item.audio != null) 0.55f else 0.9f))
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .testTag("lane_${(item.sel as? Selection.OfText)?.id ?: (item.sel as? Selection.OfSticker)?.id ?: (item.sel as? Selection.OfAudio)?.id}"),
    ) {
        item.audio?.let { a ->
            val peaks by produceState<FloatArray?>(null, a.path) { value = Waveforms.peaks(a.path, a.sourceDurationMs) }
            val p = peaks
            if (p != null) Canvas(Modifier.fillMaxSize().padding(horizontal = if (selected) 14.dp else 2.dp, vertical = 3.dp)) {
                val from = (a.inMs / Waveforms.BUCKET_MS).toInt().coerceIn(0, p.size - 1)
                val to = (a.outMs / Waveforms.BUCKET_MS).toInt().coerceIn(from + 1, p.size)
                val n = to - from
                val bw = size.width / n
                val barW = max(1f, min(bw * 0.7f, 3f))
                var i = 0
                val stride = max(1, (3f / max(bw, 0.01f)).toInt())
                while (i < n) {
                    val h = max(2f, p[from + i] * size.height)
                    val x = i * bw
                    drawLine(Color(0xCCFFFFFF), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = barW)
                    i += stride
                }
            }
        }
        Text(
            item.label, color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = if (selected) 18.dp else 6.dp, end = 4.dp),
        )
        if (selected) {
            Handle(Modifier.align(Alignment.CenterStart).width(12.dp))
            Handle(Modifier.align(Alignment.CenterEnd).width(12.dp))
        }
    }
}
