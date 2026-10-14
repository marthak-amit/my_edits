package com.myedits.app.data

import kotlinx.serialization.Serializable

/** Output canvas shapes offered by the editor. [ratio] is width / height. */
@Serializable
enum class AspectRatio(val label: String, val ratio: Float) {
    R9_16("9:16", 9f / 16f),
    R1_1("1:1", 1f),
    R4_5("4:5", 4f / 5f),
    R16_9("16:9", 16f / 9f),
    R3_4("3:4", 3f / 4f),
}

@Serializable
enum class FitMode { FIT, FILL }

@Serializable
enum class FontStyleId(val label: String) {
    CLASSIC("Classic"),
    MODERN("Modern"),
    SERIF("Serif"),
    MONO("Typewriter"),
    BOLD("Poster"),
    SCRIPT("Script"),
}

@Serializable
enum class TextBg { NONE, SOLID, ROUNDED }

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class AudioKind(val label: String) {
    MUSIC("Music"),
    SFX("Sound"),
    VOICE("Voiceover"),
    EXTRACTED("Extracted"),
    TTS("Speech"),
}

@Serializable
enum class StickerKind { EMOJI, IMAGE }

/** What happens at the cut from a clip to the next one. Drawn around the boundary on the whole frame. */
@Serializable
enum class Transition(val label: String, val emoji: String) {
    NONE("None", "⏹"),
    FADE("Fade", "🌑"),
    FLASH("Flash", "⚡"),
    ZOOM("Zoom punch", "🔍"),
    SLIDE("Slide", "➡️"),
    SPIN("Spin", "🌀"),
}

/** Slow movement across a whole clip (the "Ken Burns" family). */
@Serializable
enum class ClipAnim(val label: String, val emoji: String) {
    NONE("None", "⏹"),
    ZOOM_IN("Zoom in", "➕"),
    ZOOM_OUT("Zoom out", "➖"),
    PAN_LEFT("Pan left", "⬅️"),
    PAN_RIGHT("Pan right", "➡️"),
    PULSE("Pulse", "💓"),
}

/** Entrance (and exit) animation for text and stickers. */
@Serializable
enum class LayerAnim(val label: String) {
    NONE("None"), FADE("Fade"), POP("Pop"), SLIDE_UP("Slide up"), SLIDE_LEFT("Slide in"),
}

/** Basic colour adjustments. All values in -1..1 where 0 is neutral. */
@Serializable
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f &&
            exposure == 0f && warmth == 0f && tint == 0f
}

@Serializable
data class Clip(
    val id: String,
    /** Absolute path of the app-private media copy. */
    val path: String,
    val isImage: Boolean = false,
    val sourceDurationMs: Long,
    val inMs: Long = 0L,
    val outMs: Long = sourceDurationMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val fit: FitMode = FitMode.FIT,
    val zoom: Float = 1f,
    val filter: String = "none",
    val filterIntensity: Float = 1f,
    val adjust: Adjust = Adjust(),
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    /** Offsets inside the zoomed frame, -1..1 (only has an effect when [zoom] > 1). */
    val panX: Float = 0f,
    val panY: Float = 0f,
    val transition: Transition = Transition.NONE,
    val transitionMs: Long = 500L,
    val animation: ClipAnim = ClipAnim.NONE,
    /** 0..1 strength of each look effect. */
    val blur: Float = 0f,
    val vignette: Float = 0f,
    val bars: Boolean = false,
) {
    /** Length on the timeline after the speed change. */
    val durationMs: Long get() = ((outMs - inMs) / speed).toLong().coerceAtLeast(1L)
}

@Serializable
data class TextLayer(
    val id: String,
    val text: String,
    val startMs: Long,
    val durationMs: Long,
    /** Centre of the layer, 0..1 of canvas width/height. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Multiplier on the base font size (canvas height * 0.055). */
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val color: Long = 0xFFFFFFFF,
    val bgColor: Long = 0xFF000000,
    val bg: TextBg = TextBg.NONE,
    val font: FontStyleId = FontStyleId.MODERN,
    val align: TextAlign = TextAlign.CENTER,
    val outline: Boolean = false,
    val shadow: Boolean = true,
    val anim: LayerAnim = LayerAnim.NONE,
    val opacity: Float = 1f,
) {
    val endMs: Long get() = startMs + durationMs
}

@Serializable
data class StickerLayer(
    val id: String,
    val kind: StickerKind,
    val emoji: String = "",
    val path: String = "",
    val startMs: Long,
    val durationMs: Long,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Fraction of canvas width the sticker's longest side occupies at scale 1. */
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val anim: LayerAnim = LayerAnim.NONE,
    val opacity: Float = 1f,
) {
    val endMs: Long get() = startMs + durationMs
}

@Serializable
data class AudioTrack(
    val id: String,
    val path: String,
    val label: String,
    val kind: AudioKind,
    val sourceDurationMs: Long,
    /** Where the track begins on the project timeline. */
    val startMs: Long = 0L,
    val inMs: Long = 0L,
    val outMs: Long = sourceDurationMs,
    val volume: Float = 1f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    /** 1 = natural; >1 higher (chipmunk), <1 lower. Changes pitch without changing speed. */
    val pitch: Float = 1f,
) {
    val durationMs: Long get() = (outMs - inMs).coerceAtLeast(1L)
    val endMs: Long get() = startMs + durationMs
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val aspect: AspectRatio = AspectRatio.R9_16,
    val background: Long = 0xFF000000,
    val clips: List<Clip> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    val stickers: List<StickerLayer> = emptyList(),
    val audios: List<AudioTrack> = emptyList(),
    val exportCount: Int = 0,
    /** Id of the template this project was created from (empty when started from scratch). */
    val template: String = "",
) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }
    val isEmpty: Boolean get() = clips.isEmpty()
}

/** Where a clip sits on the timeline. */
data class ClipSpan(val index: Int, val clip: Clip, val startMs: Long) {
    val endMs: Long get() = startMs + clip.durationMs
}
