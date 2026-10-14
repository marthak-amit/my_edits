package com.myedits.app.engine

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix

/** One clip's place on the timeline plus its fade lengths, all in microseconds. */
data class FadeSegment(val startUs: Long, val endUs: Long, val fadeInUs: Long, val fadeOutUs: Long)

object FadeMath {
    fun gain(segments: List<FadeSegment>, tUs: Long): Float {
        val seg = segments.firstOrNull { tUs >= it.startUs && tUs < it.endUs } ?: return 1f
        var g = 1f
        if (seg.fadeInUs > 0) g = minOf(g, (tUs - seg.startUs).toFloat() / seg.fadeInUs)
        if (seg.fadeOutUs > 0) g = minOf(g, (seg.endUs - tUs).toFloat() / seg.fadeOutUs)
        return g.coerceIn(0f, 1f)
    }
}

/**
 * Composition-level fade to / from black. Using absolute timeline timestamps means the
 * fades are right regardless of how individual items are offset inside the pipeline.
 */
@UnstableApi
class FadeEffect(private val segments: List<FadeSegment>) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = FadeMath.gain(segments, presentationTimeUs)
        return if (g >= 0.999f) ColorMath.IDENTITY else ColorMath.gain(g)
    }
}
