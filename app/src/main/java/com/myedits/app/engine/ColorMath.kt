package com.myedits.app.engine

import com.myedits.app.data.Adjust
import kotlin.math.pow

/**
 * Colour processing expressed as affine 4x4 matrices (column-major, applied to [r, g, b, 1]),
 * which is exactly what Media3's `RgbMatrix` and Android's `ColorMatrix` consume.
 */
object ColorMath {
    val IDENTITY: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /** a * b (apply b first, then a), column-major. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
        return out
    }

    fun scaleRgb(r: Float, g: Float, b: Float): FloatArray = IDENTITY.copyOf().also { it[0] = r; it[5] = g; it[10] = b }

    fun offsetRgb(r: Float, g: Float, b: Float): FloatArray = IDENTITY.copyOf().also { it[12] = r; it[13] = g; it[14] = b }

    fun saturation(s: Float): FloatArray {
        val inv = 1f - s
        val r = inv * LR; val g = inv * LG; val b = inv * LB
        return floatArrayOf(
            r + s, r, r, 0f,
            g, g + s, g, 0f,
            b, b, b + s, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /** Contrast about mid-grey. [k] = 1 is neutral. */
    fun contrast(k: Float): FloatArray = multiply(offsetRgb(0.5f, 0.5f, 0.5f), multiply(scaleRgb(k, k, k), offsetRgb(-0.5f, -0.5f, -0.5f)))

    val SEPIA: FloatArray = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f,
        0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun adjustMatrix(a: Adjust): FloatArray {
        if (a.isNeutral) return IDENTITY
        var m = IDENTITY
        if (a.exposure != 0f) {
            val g = 2f.pow(a.exposure)
            m = multiply(scaleRgb(g, g, g), m)
        }
        if (a.contrast != 0f) m = multiply(contrast(1f + a.contrast * 0.8f), m)
        if (a.saturation != 0f) m = multiply(saturation(1f + a.saturation), m)
        if (a.warmth != 0f) m = multiply(scaleRgb(1f + a.warmth * 0.25f, 1f, 1f - a.warmth * 0.25f), m)
        if (a.tint != 0f) m = multiply(scaleRgb(1f + a.tint * 0.12f, 1f - a.tint * 0.2f, 1f + a.tint * 0.12f), m)
        if (a.brightness != 0f) m = multiply(offsetRgb(a.brightness * 0.35f, a.brightness * 0.35f, a.brightness * 0.35f), m)
        return m
    }

    fun filterMatrix(id: String): FloatArray = FilterPresets.byId(id).matrix

    /** Final per-clip matrix: preset (blended by [intensity]) followed by manual adjustments. */
    fun clipMatrix(filterId: String, intensity: Float, adjust: Adjust): FloatArray {
        val preset = lerp(IDENTITY, filterMatrix(filterId), intensity.coerceIn(0f, 1f))
        return multiply(adjustMatrix(adjust), preset)
    }

    fun isIdentity(m: FloatArray, eps: Float = 1e-4f): Boolean = m.indices.all { kotlin.math.abs(m[it] - IDENTITY[it]) < eps }

    fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(16) { a[it] + (b[it] - a[it]) * t }

    /** Converts to Android's 4x5 row-major ColorMatrix layout (values scaled to 0..255 offsets). */
    fun toAndroidColorMatrix(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[4], m[8], 0f, m[12] * 255f,
        m[1], m[5], m[9], 0f, m[13] * 255f,
        m[2], m[6], m[10], 0f, m[14] * 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Uniform RGB gain used by fades (0 = black, 1 = untouched). */
    fun gain(g: Float): FloatArray = scaleRgb(g, g, g)
}

data class FilterPreset(val id: String, val label: String, val matrix: FloatArray)

object FilterPresets {
    private fun m(sat: Float = 1f, con: Float = 1f, br: Float = 0f, warm: Float = 0f, tint: Float = 0f): FloatArray {
        var x = ColorMath.IDENTITY
        x = ColorMath.multiply(ColorMath.saturation(sat), x)
        x = ColorMath.multiply(ColorMath.contrast(con), x)
        x = ColorMath.multiply(ColorMath.scaleRgb(1f + warm * 0.25f, 1f - kotlin.math.abs(tint) * 0.05f, 1f - warm * 0.25f), x)
        x = ColorMath.multiply(ColorMath.scaleRgb(1f + tint * 0.12f, 1f - tint * 0.2f, 1f + tint * 0.12f), x)
        x = ColorMath.multiply(ColorMath.offsetRgb(br, br, br), x)
        return x
    }

    val all: List<FilterPreset> = listOf(
        FilterPreset("none", "Original", ColorMath.IDENTITY),
        FilterPreset("vivid", "Vivid", m(sat = 1.45f, con = 1.12f)),
        FilterPreset("warm", "Warm", m(sat = 1.1f, warm = 0.6f, br = 0.02f)),
        FilterPreset("cool", "Cool", m(sat = 1.05f, warm = -0.6f)),
        FilterPreset("sunset", "Sunset", m(sat = 1.25f, warm = 0.8f, tint = 0.5f, con = 1.05f)),
        FilterPreset("mint", "Mint", m(sat = 1.1f, warm = -0.3f, tint = -0.5f)),
        FilterPreset("dream", "Dream", m(sat = 1.2f, con = 0.9f, br = 0.07f, tint = 0.35f)),
        FilterPreset("fade", "Fade", m(sat = 0.85f, con = 0.78f, br = 0.08f)),
        FilterPreset("drama", "Drama", m(sat = 0.8f, con = 1.35f, br = -0.03f)),
        FilterPreset("retro", "Retro", ColorMath.lerp(ColorMath.IDENTITY, ColorMath.multiply(ColorMath.offsetRgb(0.04f, 0.02f, 0f), ColorMath.multiply(ColorMath.contrast(0.9f), ColorMath.SEPIA)), 0.65f)),
        FilterPreset("sepia", "Sepia", ColorMath.SEPIA),
        FilterPreset("mono", "Mono", m(sat = 0f)),
        FilterPreset("noir", "Noir", m(sat = 0f, con = 1.4f, br = -0.04f)),
    )

    fun byId(id: String): FilterPreset = all.firstOrNull { it.id == id } ?: all.first()
}
