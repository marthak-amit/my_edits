package com.myedits.app.data

import com.myedits.app.engine.ProjectOps

/** Starting points shown in the Inspiration tab. Applied to the first media added to a project. */
data class Template(
    val id: String,
    val title: String,
    val emoji: String,
    val description: String,
    val aspect: AspectRatio,
    val filter: String = "none",
    val intensity: Float = 1f,
    val fadeMs: Long = 0L,
    val caption: String? = null,
    val tip: String,
)

object Templates {
    val all = listOf(
        Template("reel", "Reel starter", "🎬", "Vertical 9:16 with a punchy vivid look and a title.", AspectRatio.R9_16, "vivid", 0.8f, 300, "Your title", "Keep clips under 3 seconds each for fast pacing."),
        Template("cinematic", "Cinematic", "🎞️", "Wide 16:9, drama grade and slow fades in and out.", AspectRatio.R16_9, "drama", 0.9f, 900, null, "Slow clips to 0.5x for a dreamy feel."),
        Template("travel", "Travel diary", "🌴", "Warm sunset tones with a location caption.", AspectRatio.R4_5, "sunset", 0.7f, 400, "📍 Somewhere beautiful", "Add a voiceover to tell the story behind each shot."),
        Template("retro", "Retro memory", "📼", "Square 1:1 with a faded sepia look.", AspectRatio.R1_1, "retro", 1f, 500, "Remember when", "Layer an old-school sticker on top."),
        Template("mono", "Black & white", "🖤", "Timeless noir contrast for portraits.", AspectRatio.R4_5, "noir", 1f, 300, null, "Increase contrast in Adjust for a moodier result."),
        Template("blank", "Blank canvas", "⬜", "Start from scratch with no preset.", AspectRatio.R9_16, "none", 1f, 0, null, "Tap Add to bring in your first clips."),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Applies the project's pending template to its clips, once, when media first arrives. */
    fun applyOnFirstImport(p: Project, wasEmpty: Boolean): Project {
        val t = byId(p.template) ?: return p
        if (!wasEmpty || p.clips.isEmpty()) return p
        var out = p.copy(template = "", aspect = t.aspect)
        out = ProjectOps.updateAllClips(out) { c ->
            c.copy(filter = t.filter, filterIntensity = t.intensity).let { cc ->
                if (t.fadeMs > 0 && cc.durationMs > t.fadeMs * 2) cc.copy(fadeInMs = t.fadeMs, fadeOutMs = t.fadeMs) else cc
            }
        }
        if (t.caption != null) out = ProjectOps.addText(out, ProjectOps.defaultText(out, 0, t.caption).copy(y = 0.82f))
        return out
    }
}
