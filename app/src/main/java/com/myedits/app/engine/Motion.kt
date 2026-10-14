package com.myedits.app.engine

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.myedits.app.data.ClipAnim
import com.myedits.app.data.LayerAnim
import com.myedits.app.data.Transition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Frame motion in normalised device space: scale, translation (1.0 = half the frame) and rotation. */
data class Motion(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f, val rotDeg: Float = 0f) {
    operator fun plus(o: Motion) = Motion(scale * o.scale, tx + o.tx, ty + o.ty, rotDeg + o.rotDeg)
    val isIdentity: Boolean get() = scale == 1f && tx == 0f && ty == 0f && rotDeg == 0f
}

/** A clip's span on the timeline plus its slow-move animation. */
data class AnimSegment(val startUs: Long, val endUs: Long, val anim: ClipAnim)

/** A cut between two clips: [halfUs] either side of [boundaryUs] is the transition window. */
data class TransitionSegment(val boundaryUs: Long, val halfUs: Long, val type: Transition)

object MotionMath {
    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Motion of a clip animation at progress [p] (0 at the clip's first frame, 1 at its last). */
    fun clipAnim(anim: ClipAnim, p: Float): Motion {
        val q = p.coerceIn(0f, 1f)
        return when (anim) {
            ClipAnim.NONE -> Motion()
            ClipAnim.ZOOM_IN -> Motion(scale = 1f + 0.22f * q)
            ClipAnim.ZOOM_OUT -> Motion(scale = 1.22f - 0.22f * q)
            ClipAnim.PAN_LEFT -> Motion(scale = 1.18f, tx = 0.16f * (1 - 2 * q))
            ClipAnim.PAN_RIGHT -> Motion(scale = 1.18f, tx = -0.16f * (1 - 2 * q))
            ClipAnim.PULSE -> Motion(scale = 1f + 0.06f * abs(sin(q * PI.toFloat() * 4)))
        }
    }

    /**
     * Motion of a transition. [phase] runs -1 (window starts) .. 0 (the cut) .. +1 (window ends).
     * Every transition is continuous through the cut so nothing jumps.
     */
    fun transition(type: Transition, phase: Float): Motion {
        val u = 1f - abs(phase.coerceIn(-1f, 1f)) // 0 at the window edge, 1 at the cut
        val e = u * u
        return when (type) {
            Transition.ZOOM -> Motion(scale = 1f + 0.8f * e)
            // leaves to the left before the cut and enters from the right after it; off-screen at the cut itself
            Transition.SLIDE -> Motion(tx = if (phase <= 0f) -2f * e else 2f * e)
            Transition.SPIN -> Motion(scale = 1f + 0.5f * e, rotDeg = -60f * e)
            Transition.NONE, Transition.FADE, Transition.FLASH -> Motion()
        }
    }

    /** Dip amounts for the colour transitions: (gain towards black, mix towards white). */
    fun transitionColor(type: Transition, phase: Float): Pair<Float, Float> {
        val a = abs(phase.coerceIn(-1f, 1f)) // 1 at the window edge, 0 at the cut
        return when (type) {
            Transition.FADE -> smooth(a) to 0f
            Transition.FLASH -> 1f to smooth(1f - a)
            else -> 1f to 0f
        }
    }

    private fun windowPhase(t: Long, s: TransitionSegment): Float? {
        if (s.halfUs <= 0 || t < s.boundaryUs - s.halfUs || t > s.boundaryUs + s.halfUs) return null
        return (t - s.boundaryUs).toFloat() / s.halfUs
    }

    fun motionAt(tUs: Long, anims: List<AnimSegment>, transitions: List<TransitionSegment>): Motion {
        var m = Motion()
        anims.firstOrNull { tUs >= it.startUs && tUs < it.endUs }?.let {
            m += clipAnim(it.anim, (tUs - it.startUs).toFloat() / max(1L, it.endUs - it.startUs))
        }
        transitions.forEach { s -> windowPhase(tUs, s)?.let { m += transition(s.type, it) } }
        return m
    }

    /** (gain, white) at [tUs] from all colour transitions; (1, 0) is untouched. */
    fun colorAt(tUs: Long, transitions: List<TransitionSegment>): Pair<Float, Float> {
        var g = 1f; var w = 0f
        transitions.forEach { s ->
            windowPhase(tUs, s)?.let { ph -> val (gg, ww) = transitionColor(s.type, ph); g = min(g, gg); w = max(w, ww) }
        }
        return g to w
    }

    /** Builds the transition list from clip spans (clip i's `transition` is the cut into clip i+1). */
    fun transitionSegments(spans: List<com.myedits.app.data.ClipSpan>): List<TransitionSegment> =
        spans.zipWithNext().mapNotNull { (a, b) ->
            val type = a.clip.transition
            if (type == Transition.NONE) null
            else {
                val half = min(a.clip.transitionMs / 2, min(a.clip.durationMs, b.clip.durationMs) / 2).coerceAtLeast(0L)
                if (half <= 0L) null else TransitionSegment(a.endMs * 1000, half * 1000, type)
            }
        }

    fun animSegments(spans: List<com.myedits.app.data.ClipSpan>): List<AnimSegment> =
        spans.filter { it.clip.animation != ClipAnim.NONE }.map { AnimSegment(it.startMs * 1000, it.endMs * 1000, it.clip.animation) }
}

/**
 * Whole-frame motion for clip animations and transitions. Uses absolute timeline time and the canvas
 * aspect (so a spin rotates in pixel space rather than skewing).
 */
@UnstableApi
class MotionEffect(
    private val anims: List<AnimSegment>,
    private val transitions: List<TransitionSegment>,
    private val aspect: Float,
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val m = MotionMath.motionAt(presentationTimeUs, anims, transitions)
        val out = Matrix()
        if (m.isIdentity) return out
        // work in square pixel units so rotation doesn't skew, then convert back
        out.postScale(aspect, 1f)
        out.postRotate(m.rotDeg)
        out.postScale(1f / aspect, 1f)
        out.postScale(m.scale, m.scale)
        out.postTranslate(m.tx, m.ty)
        return out
    }
}

/** Pose of a text/sticker layer at a moment inside its time window. */
data class LayerPose(val alpha: Float, val scale: Float, val dx: Float, val dy: Float)

object LayerAnimMath {
    private const val IN_MS = 350.0
    private const val OUT_MS = 250.0

    private fun easeOutBack(x: Double): Double { val c1 = 1.70158; val c3 = c1 + 1; val t = x - 1; return 1 + c3 * t * t * t + c1 * t * t }

    /** [dx]/[dy] are fractions of the canvas width/height; y grows downward. */
    fun pose(anim: LayerAnim, tMs: Long, startMs: Long, endMs: Long, opacity: Float = 1f): LayerPose {
        val dur = (endMs - startMs).coerceAtLeast(1L)
        val inMs = min(IN_MS, dur / 2.5)
        val outMs = min(OUT_MS, dur / 4.0)
        val sinceStart = (tMs - startMs).toDouble().coerceAtLeast(0.0)
        val toEnd = (endMs - tMs).toDouble().coerceAtLeast(0.0)
        val pIn = (sinceStart / inMs).coerceIn(0.0, 1.0)
        val pOut = (toEnd / outMs).coerceIn(0.0, 1.0)
        return when (anim) {
            LayerAnim.NONE -> LayerPose(opacity, 1f, 0f, 0f)
            LayerAnim.FADE -> LayerPose((opacity * min(pIn, pOut)).toFloat(), 1f, 0f, 0f)
            LayerAnim.POP -> LayerPose((opacity * min(pIn * 1.5, 1.0).coerceAtMost(pOut)).toFloat(), (0.55 + 0.45 * easeOutBack(pIn)).toFloat(), 0f, 0f)
            LayerAnim.SLIDE_UP -> LayerPose((opacity * min(pIn, pOut)).toFloat(), 1f, 0f, (0.07 * (1 - pIn)).toFloat())
            LayerAnim.SLIDE_LEFT -> LayerPose((opacity * min(pIn, pOut)).toFloat(), 1f, (0.12 * (1 - pIn)).toFloat(), 0f)
        }
    }
}
