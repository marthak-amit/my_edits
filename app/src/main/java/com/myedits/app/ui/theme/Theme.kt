package com.myedits.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object EditsColors {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF121214)
    val Surface2 = Color(0xFF1C1C1F)
    val Surface3 = Color(0xFF2A2A2E)
    val Outline = Color(0xFF2F2F34)
    val Text = Color(0xFFFFFFFF)
    val TextDim = Color(0xFFA8A8B0)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF453A)
    val Green = Color(0xFF32D74B)
    val Pink = Color(0xFFFF2D87)
    val Orange = Color(0xFFFF9F0A)
    val Purple = Color(0xFF8E4DFF)
    val ClipBlue = Color(0xFF2B4C7E)
    val TextLayer = Color(0xFF8E4DFF)
    val StickerLayer = Color(0xFFFF9F0A)
    val AudioMusic = Color(0xFF0FA3A3)
    val AudioVoice = Color(0xFFE5484D)
    val AudioSfx = Color(0xFF3E63DD)
    val Brand = Brush.linearGradient(listOf(Color(0xFFFF9F0A), Color(0xFFFF2D87), Color(0xFF8E4DFF)))
}

private val DarkScheme = darkColorScheme(
    primary = EditsColors.Blue,
    onPrimary = Color.White,
    secondary = EditsColors.Pink,
    background = EditsColors.Bg,
    onBackground = EditsColors.Text,
    surface = EditsColors.Surface,
    onSurface = EditsColors.Text,
    surfaceVariant = EditsColors.Surface2,
    onSurfaceVariant = EditsColors.TextDim,
    outline = EditsColors.Outline,
    error = EditsColors.Red,
)

private val EditsTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)

/** Edits is a dark-only app, like the original. */
@Composable
fun MyEditsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = EditsTypography, content = content)
}
