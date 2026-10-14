package com.myedits.app

import com.myedits.app.data.Adjust
import com.myedits.app.engine.*
import org.junit.Assert.*
import org.junit.Test

class ColorAndAudioMathTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray {
        fun row(i: Int) = m[i] * r + m[4 + i] * g + m[8 + i] * b + m[12 + i]
        return floatArrayOf(row(0), row(1), row(2))
    }

    @Test fun identityIsNoOp() {
        assertTrue(ColorMath.isIdentity(ColorMath.clipMatrix("none", 1f, Adjust())))
    }

    @Test fun neutralAdjustIsIdentity() = assertTrue(ColorMath.isIdentity(ColorMath.adjustMatrix(Adjust())))

    @Test fun zeroIntensityEqualsNoFilter() {
        FilterPresets.all.forEach { assertTrue(it.id, ColorMath.isIdentity(ColorMath.clipMatrix(it.id, 0f, Adjust()))) }
    }

    @Test fun monoFilterRemovesColour() {
        val out = apply(ColorMath.clipMatrix("mono", 1f, Adjust()), 0.9f, 0.2f, 0.1f)
        assertEquals(out[0], out[1], 1e-3f); assertEquals(out[1], out[2], 1e-3f)
    }

    @Test fun saturationZeroGivesLuma() {
        val out = apply(ColorMath.saturation(0f), 1f, 0f, 0f)
        assertEquals(0.2126f, out[0], 1e-4f); assertEquals(0.2126f, out[2], 1e-4f)
    }

    @Test fun contrastKeepsMidGreyFixed() {
        val out = apply(ColorMath.contrast(1.7f), 0.5f, 0.5f, 0.5f)
        out.forEach { assertEquals(0.5f, it, 1e-4f) }
        val bright = apply(ColorMath.contrast(1.7f), 0.75f, 0.75f, 0.75f)
        assertTrue(bright[0] > 0.75f)
    }

    @Test fun brightnessAndExposureMoveInTheRightDirection() {
        assertTrue(apply(ColorMath.adjustMatrix(Adjust(brightness = 0.5f)), 0.3f, 0.3f, 0.3f)[0] > 0.3f)
        assertTrue(apply(ColorMath.adjustMatrix(Adjust(brightness = -0.5f)), 0.3f, 0.3f, 0.3f)[0] < 0.3f)
        assertTrue(apply(ColorMath.adjustMatrix(Adjust(exposure = 1f)), 0.2f, 0.2f, 0.2f)[0] > 0.35f)
    }

    @Test fun warmthShiftsRedUpBlueDown() {
        val out = apply(ColorMath.adjustMatrix(Adjust(warmth = 1f)), 0.5f, 0.5f, 0.5f)
        assertTrue(out[0] > 0.5f); assertTrue(out[2] < 0.5f)
    }

    @Test fun multiplyIdentityLeavesMatrixUnchanged() {
        val m = ColorMath.filterMatrix("vivid")
        assertArrayEquals(m, ColorMath.multiply(ColorMath.IDENTITY, m), 1e-6f)
        assertArrayEquals(m, ColorMath.multiply(m, ColorMath.IDENTITY), 1e-6f)
    }

    @Test fun unknownFilterFallsBackToOriginal() {
        assertEquals("none", FilterPresets.byId("does-not-exist").id)
    }

    @Test fun androidColorMatrixConversionKeepsOffsetsScaled() {
        val cm = ColorMath.toAndroidColorMatrix(ColorMath.offsetRgb(0.1f, 0.2f, 0.3f))
        assertEquals(25.5f, cm[4], 1e-3f); assertEquals(51f, cm[9], 1e-3f); assertEquals(76.5f, cm[14], 1e-3f)
        assertEquals(1f, cm[0], 0f); assertEquals(1f, cm[18], 0f)
    }

    // ---- audio gain curve ----
    @Test fun gainCurveConstantVolume() = assertEquals(0.5f, GainCurve.gainAt(1234.0, 5000, 0.5f, 0, 0), 1e-6f)

    @Test fun gainCurveFadeInRamps() {
        assertEquals(0f, GainCurve.gainAt(0.0, 5000, 1f, 1000, 0), 1e-6f)
        assertEquals(0.5f, GainCurve.gainAt(500.0, 5000, 1f, 1000, 0), 1e-6f)
        assertEquals(1f, GainCurve.gainAt(2000.0, 5000, 1f, 1000, 0), 1e-6f)
    }

    @Test fun gainCurveFadeOutRamps() {
        assertEquals(1f, GainCurve.gainAt(3999.0, 5000, 1f, 0, 1000), 1e-6f)
        assertEquals(0.5f, GainCurve.gainAt(4500.0, 5000, 1f, 0, 1000), 1e-6f)
        assertEquals(0f, GainCurve.gainAt(5000.0, 5000, 1f, 0, 1000), 1e-6f)
    }

    @Test fun gainCurveScalesFadeByVolume() = assertEquals(0.8f, GainCurve.gainAt(5000.0, 10000, 0.8f, 1000, 1000), 1e-6f)

    // ---- video fade ----
    private val segs = listOf(FadeSegment(0, 2_000_000, 500_000, 500_000), FadeSegment(2_000_000, 4_000_000, 0, 0))

    @Test fun fadeGainOnlyAffectsFadingSegment() {
        assertEquals(0f, FadeMath.gain(segs, 0), 1e-6f)
        assertEquals(0.5f, FadeMath.gain(segs, 250_000), 1e-6f)
        assertEquals(1f, FadeMath.gain(segs, 1_000_000), 1e-6f)
        assertEquals(0.5f, FadeMath.gain(segs, 1_750_000), 1e-6f)
        assertEquals(1f, FadeMath.gain(segs, 3_000_000), 1e-6f)
        assertEquals(1f, FadeMath.gain(segs, 9_000_000), 1e-6f)
    }
}
