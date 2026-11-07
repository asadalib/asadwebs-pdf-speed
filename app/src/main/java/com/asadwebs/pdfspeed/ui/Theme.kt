package com.asadwebs.pdfspeed.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.asadwebs.pdfspeed.R

val Cream = Color(0xFFF4F0E8)
val CardBg = Color(0xFFFFFEFB)
val Gold = Color(0xFFC8A86C)
val GoldDeep = Color(0xFFA98850)
val GoldInk = Color(0xFF26200F)
val Ink = Color(0xFF1B1815)
val Muted = Color(0xFF8A8172)
val Line = Color(0xFFE2D9C6)
val NoteBg = Color(0xFFFAF3DD)

val Fraunces = FontFamily(
    Font(R.font.fraunces_400, FontWeight.Normal),
    Font(R.font.fraunces_600, FontWeight.SemiBold),
    Font(R.font.fraunces_700, FontWeight.Bold)
)

private val Scheme = lightColorScheme(
    primary = Gold, onPrimary = GoldInk,
    background = Cream, onBackground = Ink,
    surface = CardBg, onSurface = Ink,
    surfaceVariant = NoteBg, onSurfaceVariant = Ink,
    outline = Line
)

private val Type = Typography(
    displayMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 42.sp, lineHeight = 45.sp),
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, color = Muted),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.2.sp, color = GoldDeep)
)

@Composable
fun PDFSpeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
