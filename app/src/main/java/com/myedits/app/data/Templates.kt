package com.myedits.app.data

import com.myedits.app.engine.ProjectOps

enum class TemplateCategory(val label: String) {
    TRENDING("Trending"), TRAVEL("Travel"), CINEMATIC("Cinematic"), MEMORIES("Memories"), LIFESTYLE("Lifestyle"),
}

/** One "Add a clip here" position of a template: how long it lasts and how it looks and moves. */
data class TemplateSlot(
    val ms: Long,
    /** Transition into the next slot. */
    val transition: Transition = Transition.NONE,
    val anim: ClipAnim = ClipAnim.NONE,
    val speed: Float = 1f,
    val caption: String? = null,
    /** Short sound effect played as this slot begins (id from the sound library). */
    val sfx: String? = null,
)

/**
 * An Edits-style template: a fixed number of clip slots with timings, transitions, motion, captions,
 * a colour look and a music bed. The user only supplies the footage.
 */
data class Template(
    val id: String,
    val title: String,
    val category: TemplateCategory,
    val emoji: String,
    val tagline: String,
    val aspect: AspectRatio,
    val slots: List<TemplateSlot>,
    val filter: String = "none",
    val intensity: Float = 1f,
    val music: String? = null,
    val vignette: Float = 0f,
    val bars: Boolean = false,
    val blur: Float = 0f,
    val captionFont: FontStyleId = FontStyleId.BOLD,
    val captionColor: Long = 0xFFFFFFFF,
    val captionAnim: LayerAnim = LayerAnim.POP,
    /** Two or three colours used for the animated preview card. */
    val palette: List<Long>,
    val tip: String,
) {
    val slotCount: Int get() = slots.size
    val totalMs: Long get() = slots.sumOf { it.ms }
    val usesCaptions: Boolean get() = slots.any { it.caption != null }
    val transitionNames: String
        get() = slots.map { it.transition }.filter { it != Transition.NONE }.distinct().joinToString(" · ") { it.label }
}

object Templates {
    private fun s(ms: Long, tr: Transition = Transition.NONE, anim: ClipAnim = ClipAnim.NONE, caption: String? = null, speed: Float = 1f, sfx: String? = null) =
        TemplateSlot(ms, tr, anim, speed, caption, sfx)

    val all: List<Template> = listOf(
        Template(
            "beat_drop", "Beat Drop", TemplateCategory.TRENDING, "🔥", "Punchy zoom cuts that land on the beat",
            AspectRatio.R9_16,
            List(6) { s(1200, Transition.ZOOM, if (it % 2 == 0) ClipAnim.ZOOM_IN else ClipAnim.ZOOM_OUT, sfx = if (it % 2 == 0) "sfx_drum" else null) },
            filter = "vivid", intensity = 0.8f, music = "music_pop", palette = listOf(0xFFFF3D71, 0xFFFF9F43, 0xFF6C5CE7),
            tip = "Pick clips with movement — the zoom cuts add the rest.",
        ),
        Template(
            "flash_cuts", "Flash Cuts", TemplateCategory.TRENDING, "⚡", "Eight quick flashes, one big finish",
            AspectRatio.R9_16,
            List(8) { s(800, Transition.FLASH, ClipAnim.PULSE) }.dropLast(1) + s(1600, anim = ClipAnim.ZOOM_IN, caption = "THE END"),
            filter = "drama", intensity = 0.7f, music = "music_retro", vignette = 0.45f, palette = listOf(0xFFFFFFFF, 0xFF00D2FF, 0xFF3A47D5),
            tip = "Photos work great here — each one only shows for under a second.",
        ),
        Template(
            "spin_cycle", "Spin Cycle", TemplateCategory.TRENDING, "🌀", "Whip-spin transitions with a pulsing beat",
            AspectRatio.R9_16,
            List(5) { s(1400, Transition.SPIN, ClipAnim.PULSE, sfx = if (it < 4) "sfx_whoosh" else null) },
            filter = "dream", intensity = 0.7f, music = "music_pop", palette = listOf(0xFF8E2DE2, 0xFF4A00E0, 0xFFFF6FD8),
            tip = "Keep the subject centred so the spin lands cleanly.",
        ),
        Template(
            "glow_up", "Glow Up", TemplateCategory.TRENDING, "✨", "Before, during, after — with a soft fade",
            AspectRatio.R4_5,
            listOf(s(2200, Transition.FADE, caption = "before"), s(2200, Transition.FADE, caption = "during"), s(2800, anim = ClipAnim.ZOOM_IN, caption = "after ✨")),
            filter = "sunset", intensity = 0.6f, music = "music_lofi", captionFont = FontStyleId.SCRIPT, captionAnim = LayerAnim.FADE,
            palette = listOf(0xFFFFC371, 0xFFFF5F6D, 0xFF2B1B3D), tip = "Use the same angle for all three clips for the best reveal.",
        ),
        Template(
            "travel_diary", "Travel Diary", TemplateCategory.TRAVEL, "🌴", "Slide through your days with location captions",
            AspectRatio.R4_5,
            listOf(
                s(2000, Transition.SLIDE, ClipAnim.PAN_LEFT, "📍 Day 1"), s(2000, Transition.SLIDE, ClipAnim.PAN_RIGHT, "📍 Day 2"),
                s(2000, Transition.SLIDE, ClipAnim.PAN_LEFT, "📍 Day 3"), s(2000, Transition.SLIDE, ClipAnim.PAN_RIGHT, "📍 Day 4"),
                s(2400, anim = ClipAnim.ZOOM_OUT, caption = "see you soon ✈️"),
            ),
            filter = "warm", intensity = 0.8f, music = "music_lofi", captionFont = FontStyleId.MODERN, captionAnim = LayerAnim.SLIDE_UP,
            palette = listOf(0xFF11998E, 0xFF38EF7D, 0xFFF9D423), tip = "Landscape clips pan best — the slow drift shows off the view.",
        ),
        Template(
            "road_trip", "Road Trip", TemplateCategory.TRAVEL, "🚗", "Widescreen drifts with a sunset grade",
            AspectRatio.R16_9,
            listOf(s(2600, Transition.FADE, ClipAnim.PAN_RIGHT), s(2600, Transition.FADE, ClipAnim.PAN_LEFT), s(2600, Transition.FADE, ClipAnim.ZOOM_IN), s(3000, anim = ClipAnim.ZOOM_OUT, caption = "the journey")),
            filter = "sunset", intensity = 0.9f, music = "music_retro", bars = true, palette = listOf(0xFFF12711, 0xFFF5AF19, 0xFF1F1C2C),
            tip = "Dashboard shots and open roads suit this one.",
        ),
        Template(
            "cinema_trailer", "Cinema Trailer", TemplateCategory.CINEMATIC, "🎬", "Letterbox, drama grade and title cards",
            AspectRatio.R16_9,
            listOf(
                s(3000, Transition.FADE, ClipAnim.ZOOM_IN, "A STORY"), s(3000, Transition.FADE, ClipAnim.ZOOM_IN, "OF COURAGE"),
                s(2400, Transition.FLASH, ClipAnim.ZOOM_OUT, "AND HOPE"), s(3600, anim = ClipAnim.ZOOM_IN, caption = "COMING SOON"),
            ),
            filter = "drama", intensity = 0.9f, music = "music_cinema", vignette = 0.5f, bars = true, captionFont = FontStyleId.SERIF, captionAnim = LayerAnim.FADE,
            palette = listOf(0xFF232526, 0xFF414345, 0xFFE0B25A), tip = "Pick your best landscape clips — the black bars are added for you.",
        ),
        Template(
            "noir", "Noir", TemplateCategory.CINEMATIC, "🖤", "Black-and-white with a heavy vignette",
            AspectRatio.R4_5,
            listOf(s(3000, Transition.FADE, ClipAnim.ZOOM_IN), s(3000, Transition.FADE, ClipAnim.PAN_LEFT), s(3500, anim = ClipAnim.ZOOM_OUT, caption = "after dark")),
            filter = "noir", intensity = 1f, music = "music_cinema", vignette = 0.7f, captionFont = FontStyleId.SERIF, captionAnim = LayerAnim.FADE,
            palette = listOf(0xFF000000, 0xFF434343, 0xFFCCCCCC), tip = "Portraits and moody street shots look best.",
        ),
        Template(
            "retro_tape", "Retro Tape", TemplateCategory.MEMORIES, "📼", "Faded VHS square with a typewriter caption",
            AspectRatio.R1_1,
            listOf(s(1800, Transition.FLASH, caption = "remember when"), s(1800, Transition.FLASH), s(1800, Transition.FLASH), s(1800, Transition.FLASH), s(2200, caption = "REWIND ⏪")),
            filter = "retro", intensity = 1f, music = "music_retro", vignette = 0.4f, captionFont = FontStyleId.MONO, captionAnim = LayerAnim.SLIDE_LEFT,
            palette = listOf(0xFFD38312, 0xFFA83279, 0xFF2C2C54), tip = "Old photos and screen-recordings both get the tape look.",
        ),
        Template(
            "year_review", "Year in Review", TemplateCategory.MEMORIES, "🎉", "Six moments, six captions, one big year",
            AspectRatio.R9_16,
            listOf("Jan – Feb", "Mar – Apr", "May – Jun", "Jul – Aug", "Sep – Oct", "Nov – Dec").mapIndexed { i, c ->
                s(if (i == 5) 1900 else 1500, if (i == 5) Transition.NONE else Transition.SLIDE, if (i % 2 == 0) ClipAnim.ZOOM_IN else ClipAnim.PAN_LEFT, c)
            },
            filter = "vivid", intensity = 0.6f, music = "music_pop", captionAnim = LayerAnim.SLIDE_UP,
            palette = listOf(0xFF00C6FF, 0xFF0072FF, 0xFFFFD200), tip = "Choose one highlight per two months, in order.",
        ),
        Template(
            "day_in_life", "Day in My Life", TemplateCategory.LIFESTYLE, "☀️", "Morning to night, one caption per moment",
            AspectRatio.R9_16,
            listOf("☀️ morning", "☕ coffee", "💻 work", "🍜 lunch", "🏃 move", "🌙 night").map { s(1500, Transition.FADE, ClipAnim.ZOOM_IN, it) },
            filter = "warm", intensity = 0.6f, music = "music_lofi", captionFont = FontStyleId.MODERN, captionAnim = LayerAnim.SLIDE_UP,
            palette = listOf(0xFFFDC830, 0xFFF37335, 0xFF4B6CB7), tip = "Short, steady clips (2–3 s) of each part of your day.",
        ),
        Template(
            "photo_dump", "Photo Dump", TemplateCategory.LIFESTYLE, "📸", "Seven snaps with a pop on every cut",
            AspectRatio.R4_5,
            List(7) { s(1000, Transition.ZOOM, ClipAnim.ZOOM_OUT, sfx = if (it % 3 == 0) "sfx_pop" else null) },
            filter = "fade", intensity = 0.8f, music = "music_pop", palette = listOf(0xFFFF9A9E, 0xFFFAD0C4, 0xFFA18CD1),
            tip = "Mix portrait and landscape — each is filled to the frame.",
        ),
    )

    val categories: List<TemplateCategory> get() = TemplateCategory.entries.filter { c -> all.any { it.category == c } }

    fun lookName(t: Template): String =
        (if (t.filter == "none") "Natural" else com.myedits.app.engine.FilterPresets.byId(t.filter).label) +
            (if (t.bars) " · cinema bars" else "") + (if (t.vignette > 0f) " · vignette" else "")

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }

    fun inCategory(c: TemplateCategory?): List<Template> = if (c == null) all else all.filter { it.category == c }

    /** Slot used for the [i]th clip: slots are reused in order if the user adds more clips than slots. */
    fun slotFor(t: Template, i: Int): TemplateSlot = t.slots[i % t.slots.size]

    /**
     * Lays [clips] out on the template's slots and returns the finished project: trimmed clips, transitions, motion,
     * look, captions, vignette / bars and music. [sound] resolves a library sound id to a track starting at the given ms.
     */
    fun apply(base: Project, t: Template, clips: List<Clip>, sound: (id: String, startMs: Long) -> AudioTrack?): Project {
        require(clips.isNotEmpty()) { "A template needs at least one clip" }
        val placed = clips.mapIndexed { i, src ->
            val slot = slotFor(t, i)
            val last = i == clips.lastIndex
            val c = fit(src, slot, t).let { c ->
                val tr = if (last) Transition.NONE else slot.transition
                c.copy(
                    transition = tr,
                    transitionMs = minOf(450L, c.durationMs / 2).coerceAtLeast(100L),
                    animation = slot.anim,
                    fadeInMs = 0L,
                    fadeOutMs = if (last && c.durationMs > 1200) 500L else 0L,
                )
            }
            c
        }
        var p = base.copy(aspect = t.aspect, clips = placed, texts = emptyList(), stickers = emptyList(), audios = emptyList())
        val total = p.durationMs

        // look: filter on every clip, vignette and bars on all of them
        p = ProjectOps.updateAllClips(p) { it.copy(filter = t.filter, filterIntensity = t.intensity, vignette = t.vignette, bars = t.bars, blur = t.blur) }

        // captions and sound effects follow their slot's real position on the timeline
        var at = 0L
        placed.forEachIndexed { i, c ->
            val slot = slotFor(t, i)
            val dur = c.durationMs
            slot.caption?.let { text ->
                val big = text == text.uppercase() && text.length < 20
                val layer = TextLayer(
                    id = ProjectOps.newId(), text = text, startMs = at + 150, durationMs = (dur - 300).coerceAtLeast(ProjectOps.MIN_LAYER_MS),
                    y = if (t.bars) 0.78f else 0.82f, scale = if (big) 1.5f else 1.2f, font = t.captionFont, color = t.captionColor,
                    shadow = true, anim = t.captionAnim,
                )
                p = ProjectOps.addText(p, layer)
            }
            slot.sfx?.let { id -> sound(id, at)?.let { p = ProjectOps.addAudio(p, it.copy(volume = 0.9f)) } }
            at += dur
        }

        t.music?.let { id ->
            sound(id, 0L)?.let { m ->
                val out = minOf(m.sourceDurationMs, total).coerceAtLeast(ProjectOps.MIN_CLIP_MS * 2)
                p = ProjectOps.addAudio(p, m.copy(outMs = out, volume = 0.8f, fadeInMs = 200L, fadeOutMs = minOf(900L, out / 3)))
            }
        }
        return p.copy(template = t.id)
    }

    /** Trims [src] to the slot length (photos are simply shown that long) and fills the frame. */
    fun fit(src: Clip, slot: TemplateSlot, t: Template): Clip {
        val base = src.copy(speed = slot.speed, fit = FitMode.FILL)
        return if (src.isImage) {
            base.copy(sourceDurationMs = slot.ms, inMs = 0L, outMs = slot.ms, speed = 1f)
        } else {
            val need = (slot.ms * slot.speed).toLong()
            val out = minOf(src.sourceDurationMs, need).coerceAtLeast(ProjectOps.MIN_CLIP_MS)
            // start in the middle of long clips so the highlight, not the first frame, is used
            val spare = src.sourceDurationMs - out
            val start = if (spare > 2_000) spare / 4 else 0L
            base.copy(inMs = start, outMs = start + out)
        }
    }
}
