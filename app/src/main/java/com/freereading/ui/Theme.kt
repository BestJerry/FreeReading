package com.freereading.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Paper = Color(0xFFF7F5EF)
val Ink = Color(0xFF282D29)
val Forest = Color(0xFF456454)
val MutedInk = Color(0xFF74796F)

private val Colors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E8DB),
    onPrimaryContainer = Color(0xFF273F30),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEBE3),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFD5D8CD),
    secondary = Color(0xFF9E7250),
)

@Composable
fun FreeReadingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 44.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
        ),
        content = content,
    )
}
