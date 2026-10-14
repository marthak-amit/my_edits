package com.myedits.app.engine

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.myedits.app.data.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rasterises text and sticker layers. The same code feeds the Compose preview and the export
 * overlays so what you see while editing is exactly what ends up in the video.
 */
object LayerRenderer {

    fun typefaceFor(font: FontStyleId): Typeface = when (font) {
        FontStyleId.CLASSIC -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        FontStyleId.MODERN -> Typeface.create("sans-serif", Typeface.BOLD)
        FontStyleId.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        FontStyleId.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        FontStyleId.BOLD -> Typeface.create("sans-serif-black", Typeface.BOLD)
        FontStyleId.SCRIPT -> Typeface.create("cursive", Typeface.BOLD_ITALIC)
    }

    /** Font size in pixels for a canvas of the given height. */
    fun textSizePx(canvasH: Int, scale: Float): Float = canvasH * 0.055f * scale

    /** Renders [t] for a canvas [canvasW] x [canvasH]; the bitmap includes padding for outline/shadow. */
    fun renderText(t: TextLayer, canvasW: Int, canvasH: Int): Bitmap {
        val size = textSizePx(canvasH, t.scale)
        val pad = (size * 0.35f).roundToInt().coerceAtLeast(4)
        val maxTextW = (canvasW * 0.9f - 2 * pad).toInt().coerceAtLeast(40)

        fun paint(style: Paint.Style, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = style
            this.color = color
            textSize = size
            typeface = typefaceFor(t.font)
            if (style == Paint.Style.STROKE) { strokeWidth = size * 0.14f; strokeJoin = Paint.Join.ROUND }
        }

        val fill = paint(Paint.Style.FILL, t.color.toInt())
        if (t.shadow && t.bg == TextBg.NONE) fill.setShadowLayer(size * 0.12f, 0f, size * 0.05f, 0xAA000000.toInt())
        val text = t.text.ifEmpty { " " }
        val measured = text.split('\n').maxOf { fill.measureText(it) }.coerceAtLeast(size * 0.5f)
        val layoutW = minOf(measured.roundToInt() + 2, maxTextW)
        val alignment = when (t.align) {
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, layoutW)
            .setAlignment(alignment).setLineSpacing(0f, 1.05f).setIncludePad(false).build()

        val fillLayout = layout(fill)
        val bgPad = if (t.bg != TextBg.NONE) (size * 0.3f).roundToInt() else 0
        val w = layoutW + 2 * (pad + bgPad)
        val h = fillLayout.height + 2 * (pad + bgPad)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (t.bg != TextBg.NONE) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.bgColor.toInt() }
            val r = RectF(pad.toFloat(), pad.toFloat(), (w - pad).toFloat(), (h - pad).toFloat())
            val radius = if (t.bg == TextBg.ROUNDED) size * 0.45f else 0f
            canvas.drawRoundRect(r, radius, radius, bgPaint)
        }
        canvas.translate((pad + bgPad).toFloat(), (pad + bgPad).toFloat())
        if (t.outline) {
            val outlineColor = if (isDark(t.color.toInt())) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            layout(paint(Paint.Style.STROKE, outlineColor)).draw(canvas)
        }
        fillLayout.draw(canvas)
        return bmp
    }

    /** Emoji / image sticker. Longest side is `canvasW * scale` pixels. */
    fun renderSticker(s: StickerLayer, canvasW: Int, canvasH: Int, imageLoader: (String, Int) -> Bitmap? = ::loadScaled): Bitmap {
        val target = max(8, (canvasW * s.scale).roundToInt())
        return when (s.kind) {
            StickerKind.EMOJI -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = target * 0.82f }
                // Some emoji glyphs are wider than tall; shrink until the glyph fits the requested square.
                val firstW = paint.measureText(s.emoji).coerceAtLeast(1f)
                if (firstW > target) paint.textSize = paint.textSize * target / firstW
                val fm = paint.fontMetrics
                val textW = paint.measureText(s.emoji).coerceAtLeast(1f)
                val side = target
                val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val baseline = side / 2f - (fm.ascent + fm.descent) / 2f
                c.drawText(s.emoji, (side - textW) / 2f, baseline, paint)
                bmp
            }
            StickerKind.IMAGE -> imageLoader(s.path, target) ?: Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawColor(0x33FFFFFF)
            }
        }
    }

    /** Dark corners. [strength] 0..1. Transparent in the middle so it can sit on top of the video. */
    fun vignette(w: Int, h: Int, strength: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val radius = kotlin.math.hypot(w / 2f, h / 2f)
        val edge = Color.argb((255 * (0.15f + 0.8f * strength.coerceIn(0f, 1f))).toInt().coerceIn(0, 255), 0, 0, 0)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w / 2f, h / 2f, radius, intArrayOf(0x00000000, 0x00000000, edge), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    /** Cinema bars along the top and bottom edge. */
    fun bars(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val band = h * BARS_FRACTION
        val paint = Paint().apply { color = Color.BLACK }
        Canvas(bmp).apply { drawRect(0f, 0f, w.toFloat(), band, paint); drawRect(0f, h - band, w.toFloat(), h.toFloat(), paint) }
        return bmp
    }

    const val BARS_FRACTION = 0.115f

    /** Decodes [path] so that its longest side is about [maxSide] pixels. */
    fun loadScaled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = max(raw.width, raw.height)
        if (longest == maxSide) return raw
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(raw, max(1, (raw.width * f).roundToInt()), max(1, (raw.height * f).roundToInt()), true)
    }

    private fun isDark(argb: Int): Boolean {
        val r = Color.red(argb); val g = Color.green(argb); val b = Color.blue(argb)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 110
    }
}
