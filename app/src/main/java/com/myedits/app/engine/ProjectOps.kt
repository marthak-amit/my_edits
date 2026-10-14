package com.myedits.app.engine

import com.myedits.app.data.*
import java.util.UUID

/**
 * Pure, side-effect free editing operations. Every function takes an immutable [Project]
 * and returns a new one, which keeps undo/redo trivial and the logic unit-testable.
 */
object ProjectOps {
    const val MIN_CLIP_MS = 100L
    const val MIN_LAYER_MS = 200L
    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 10f
    const val MAX_VOLUME = 2f
    const val DEFAULT_IMAGE_MS = 3000L
    const val DEFAULT_LAYER_MS = 3000L

    fun newId(): String = UUID.randomUUID().toString().take(12)

    // ---- timeline geometry ------------------------------------------------------------

    fun spans(p: Project): List<ClipSpan> {
        var t = 0L
        return p.clips.mapIndexed { i, c ->
            ClipSpan(i, c, t).also { t += c.durationMs }
        }
    }

    /** Clip under [timeMs]. The final instant belongs to the last clip. */
    fun spanAt(p: Project, timeMs: Long): ClipSpan? {
        val all = spans(p)
        if (all.isEmpty()) return null
        val t = timeMs.coerceAtLeast(0L)
        return all.firstOrNull { t >= it.startMs && t < it.endMs } ?: all.last()
    }

    fun spanOf(p: Project, clipId: String): ClipSpan? = spans(p).firstOrNull { it.clip.id == clipId }

    // ---- main-track clip operations --------------------------------------------------

    fun appendClips(p: Project, clips: List<Clip>): Project =
        p.copy(clips = p.clips + clips).trimLayersToDuration()

    fun insertClipsAfter(p: Project, afterClipId: String?, clips: List<Clip>): Project {
        val idx = p.clips.indexOfFirst { it.id == afterClipId }
        val at = if (idx < 0) p.clips.size else idx + 1
        return p.copy(clips = p.clips.toMutableList().apply { addAll(at, clips) })
    }

    /** Splits the clip at timeline position [timeMs]. Returns null if the cut would leave a sliver. */
    fun splitAt(p: Project, timeMs: Long): Project? {
        val span = spanAt(p, timeMs) ?: return null
        val c = span.clip
        val offsetTimeline = timeMs - span.startMs
        if (offsetTimeline < MIN_CLIP_MS || c.durationMs - offsetTimeline < MIN_CLIP_MS) return null
        val sourceCut = c.inMs + (offsetTimeline * c.speed).toLong()
        val left = c.copy(outMs = sourceCut, fadeOutMs = 0L)
        val right = c.copy(id = newId(), inMs = sourceCut, fadeInMs = 0L)
        return p.copy(clips = p.clips.toMutableList().apply {
            removeAt(span.index); add(span.index, left); add(span.index + 1, right)
        })
    }

    fun updateClip(p: Project, clipId: String, f: (Clip) -> Clip): Project =
        p.copy(clips = p.clips.map { if (it.id == clipId) f(it) else it }).trimLayersToDuration()

    fun updateAllClips(p: Project, f: (Clip) -> Clip): Project =
        p.copy(clips = p.clips.map(f)).trimLayersToDuration()

    /** Trims source in/out points. [inMs]/[outMs] are in source time, not timeline time. */
    fun trimClip(p: Project, clipId: String, inMs: Long, outMs: Long): Project =
        updateClip(p, clipId) { c ->
            val limit = if (c.isImage) Long.MAX_VALUE / 4 else c.sourceDurationMs
            val i = inMs.coerceIn(0L, limit)
            val o = outMs.coerceIn(i + minSourceSpan(c), limit)
            val r = c.copy(inMs = i, outMs = o)
            r.copy(fadeInMs = r.fadeInMs.coerceAtMost(r.durationMs / 2), fadeOutMs = r.fadeOutMs.coerceAtMost(r.durationMs / 2))
        }

    private fun minSourceSpan(c: Clip): Long = (MIN_CLIP_MS * c.speed).toLong().coerceAtLeast(MIN_CLIP_MS)

    fun setSpeed(p: Project, clipId: String, speed: Float): Project =
        updateClip(p, clipId) { c ->
            val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
            val r = c.copy(speed = s)
            r.copy(fadeInMs = r.fadeInMs.coerceAtMost(r.durationMs / 2), fadeOutMs = r.fadeOutMs.coerceAtMost(r.durationMs / 2))
        }

    fun setVolume(p: Project, clipId: String, volume: Float): Project =
        updateClip(p, clipId) { it.copy(volume = volume.coerceIn(0f, MAX_VOLUME)) }

    fun deleteClip(p: Project, clipId: String): Project {
        if (spanOf(p, clipId) == null) return p
        return p.copy(clips = p.clips.filterNot { it.id == clipId }).trimLayersToDuration()
    }

    fun duplicateClip(p: Project, clipId: String): Project {
        val idx = p.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) return p
        val copy = p.clips[idx].copy(id = newId())
        return p.copy(clips = p.clips.toMutableList().apply { add(idx + 1, copy) })
    }

    fun moveClip(p: Project, clipId: String, toIndex: Int): Project {
        val from = p.clips.indexOfFirst { it.id == clipId }
        if (from < 0) return p
        val list = p.clips.toMutableList()
        val clip = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), clip)
        return p.copy(clips = list)
    }

    fun rotateClip(p: Project, clipId: String): Project =
        updateClip(p, clipId) { it.copy(rotation = (it.rotation + 90) % 360) }

    fun setFades(p: Project, clipId: String, fadeInMs: Long, fadeOutMs: Long): Project =
        updateClip(p, clipId) { c ->
            val maxEach = c.durationMs / 2
            c.copy(fadeInMs = fadeInMs.coerceIn(0L, maxEach), fadeOutMs = fadeOutMs.coerceIn(0L, maxEach))
        }

    fun setCanvas(p: Project, aspect: AspectRatio, background: Long = p.background): Project =
        p.copy(aspect = aspect, background = background)

    // ---- text / sticker layers --------------------------------------------------------

    fun addText(p: Project, text: TextLayer): Project = p.copy(texts = p.texts + clampLayer(p, text))

    fun updateText(p: Project, id: String, f: (TextLayer) -> TextLayer): Project =
        p.copy(texts = p.texts.map { if (it.id == id) clampLayer(p, f(it)) else it })

    fun deleteText(p: Project, id: String): Project = p.copy(texts = p.texts.filterNot { it.id == id })

    fun duplicateText(p: Project, id: String): Project {
        val t = p.texts.firstOrNull { it.id == id } ?: return p
        return addText(p, t.copy(id = newId(), x = (t.x + 0.05f).coerceAtMost(0.95f), y = (t.y + 0.05f).coerceAtMost(0.95f)))
    }

    fun addSticker(p: Project, s: StickerLayer): Project = p.copy(stickers = p.stickers + clampLayer(p, s))

    fun updateSticker(p: Project, id: String, f: (StickerLayer) -> StickerLayer): Project =
        p.copy(stickers = p.stickers.map { if (it.id == id) clampLayer(p, f(it)) else it })

    fun deleteSticker(p: Project, id: String): Project = p.copy(stickers = p.stickers.filterNot { it.id == id })

    fun duplicateSticker(p: Project, id: String): Project {
        val s = p.stickers.firstOrNull { it.id == id } ?: return p
        return addSticker(p, s.copy(id = newId(), x = (s.x + 0.05f).coerceAtMost(0.95f), y = (s.y + 0.05f).coerceAtMost(0.95f)))
    }

    fun defaultText(p: Project, atMs: Long, text: String = "Your text"): TextLayer {
        val total = p.durationMs
        val start = atMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val dur = minOf(DEFAULT_LAYER_MS, (total - start).coerceAtLeast(MIN_LAYER_MS))
        return TextLayer(id = newId(), text = text, startMs = start, durationMs = dur)
    }

    fun defaultSticker(p: Project, atMs: Long, emoji: String): StickerLayer {
        val total = p.durationMs
        val start = atMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val dur = minOf(DEFAULT_LAYER_MS, (total - start).coerceAtLeast(MIN_LAYER_MS))
        return StickerLayer(id = newId(), kind = StickerKind.EMOJI, emoji = emoji, startMs = start, durationMs = dur, scale = 0.28f)
    }

    fun splitText(p: Project, id: String, atMs: Long): Project? {
        val t = p.texts.firstOrNull { it.id == id } ?: return null
        if (atMs - t.startMs < MIN_LAYER_MS || t.endMs - atMs < MIN_LAYER_MS) return null
        return p.copy(texts = p.texts.flatMap {
            if (it.id != id) listOf(it)
            else listOf(it.copy(durationMs = atMs - it.startMs), it.copy(id = newId(), startMs = atMs, durationMs = it.endMs - atMs))
        })
    }

    private fun clampLayer(p: Project, t: TextLayer): TextLayer {
        val total = p.durationMs
        if (total <= 0L) return t
        val start = t.startMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val dur = t.durationMs.coerceIn(MIN_LAYER_MS, (total - start).coerceAtLeast(MIN_LAYER_MS))
        return t.copy(startMs = start, durationMs = dur, x = t.x.coerceIn(0f, 1f), y = t.y.coerceIn(0f, 1f), scale = t.scale.coerceIn(0.2f, 6f))
    }

    private fun clampLayer(p: Project, s: StickerLayer): StickerLayer {
        val total = p.durationMs
        if (total <= 0L) return s
        val start = s.startMs.coerceIn(0L, (total - MIN_LAYER_MS).coerceAtLeast(0L))
        val dur = s.durationMs.coerceIn(MIN_LAYER_MS, (total - start).coerceAtLeast(MIN_LAYER_MS))
        return s.copy(startMs = start, durationMs = dur, x = s.x.coerceIn(0f, 1f), y = s.y.coerceIn(0f, 1f), scale = s.scale.coerceIn(0.05f, 3f))
    }

    // ---- audio ------------------------------------------------------------------------

    fun addAudio(p: Project, a: AudioTrack): Project = p.copy(audios = p.audios + a)

    fun updateAudio(p: Project, id: String, f: (AudioTrack) -> AudioTrack): Project =
        p.copy(audios = p.audios.map { if (it.id == id) f(it) else it })

    fun deleteAudio(p: Project, id: String): Project = p.copy(audios = p.audios.filterNot { it.id == id })

    fun trimAudio(p: Project, id: String, inMs: Long, outMs: Long): Project =
        updateAudio(p, id) { a ->
            val i = inMs.coerceIn(0L, a.sourceDurationMs - MIN_CLIP_MS)
            val o = outMs.coerceIn(i + MIN_CLIP_MS, a.sourceDurationMs)
            a.copy(inMs = i, outMs = o, fadeInMs = a.fadeInMs.coerceAtMost((o - i) / 2), fadeOutMs = a.fadeOutMs.coerceAtMost((o - i) / 2))
        }

    fun splitAudio(p: Project, id: String, atMs: Long): Project? {
        val a = p.audios.firstOrNull { it.id == id } ?: return null
        val off = atMs - a.startMs
        if (off < MIN_CLIP_MS || a.durationMs - off < MIN_CLIP_MS) return null
        val cut = a.inMs + off
        return p.copy(audios = p.audios.flatMap {
            if (it.id != id) listOf(it)
            else listOf(it.copy(outMs = cut, fadeOutMs = 0L), it.copy(id = newId(), startMs = atMs, inMs = cut, fadeInMs = 0L))
        })
    }

    fun makeAudio(
        p: Project, path: String, label: String, kind: AudioKind, durationMs: Long, startMs: Long,
    ): AudioTrack = AudioTrack(
        id = newId(), path = path, label = label, kind = kind,
        sourceDurationMs = durationMs, startMs = startMs.coerceAtLeast(0L),
    )

    // ---- clip factories ---------------------------------------------------------------

    fun makeVideoClip(path: String, durationMs: Long, w: Int, h: Int, rotation: Int = 0): Clip =
        Clip(id = newId(), path = path, isImage = false, sourceDurationMs = durationMs.coerceAtLeast(MIN_CLIP_MS), width = w, height = h, rotation = rotation)

    fun makeImageClip(path: String, w: Int, h: Int, durationMs: Long = DEFAULT_IMAGE_MS): Clip =
        Clip(id = newId(), path = path, isImage = true, sourceDurationMs = durationMs, outMs = durationMs, width = w, height = h)

    // ---- housekeeping -----------------------------------------------------------------

    /** After the main track shrinks, layers must still lie within the project. */
    fun Project.trimLayersToDuration(): Project {
        val total = durationMs
        if (total <= 0L) return copy(texts = emptyList(), stickers = emptyList(), audios = audios)
        return copy(
            texts = texts.filter { it.startMs < total }.map { if (it.endMs > total) it.copy(durationMs = (total - it.startMs).coerceAtLeast(1L)) else it },
            stickers = stickers.filter { it.startMs < total }.map { if (it.endMs > total) it.copy(durationMs = (total - it.startMs).coerceAtLeast(1L)) else it },
            audios = audios.filter { it.startMs < total },
        )
    }
}
