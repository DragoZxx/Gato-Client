package com.gato.client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material3 mapping of the GatoClient (PC) Colors module palette
 * (see [GatoColors] for the verbatim RGBA values).
 */
private val DarkColorScheme = darkColorScheme(
    // Accents — mainColor / secondColor / Borde
    primary = GatoColors.mainColor,
    onPrimary = Color(20, 12, 32, 255),
    primaryContainer = Color(60, 40, 90, 255),
    onPrimaryContainer = Color(245, 235, 255, 255),
    secondary = GatoColors.secondColor,
    onSecondary = Color(20, 12, 32, 255),
    secondaryContainer = Color(60, 40, 90, 255),
    onSecondaryContainer = Color(245, 235, 255, 255),
    tertiary = Color(88, 45, 150, 255),
    onTertiary = Color(245, 235, 255, 255),

    // Surfaces — Fondo / Panel
    background = GatoColors.backgroundColor,
    onBackground = GatoColors.guiTextColor,
    surface = GatoColors.panelColor,
    onSurface = GatoColors.guiTextColor,

    // Panel-derived containers
    surfaceVariant = Color(48, 30, 76, 255),
    onSurfaceVariant = GatoColors.mutedTextColor,
    surfaceContainerLowest = Color(14, 8, 24, 255),
    surfaceContainerLow = Color(28, 17, 44, 255),
    surfaceContainer = GatoColors.panelColor,
    surfaceContainerHigh = Color(44, 28, 70, 255),
    surfaceContainerHighest = Color(52, 33, 84, 255),
    surfaceBright = Color(56, 36, 90, 255),
    surfaceDim = Color(16, 10, 26, 255),
    inverseSurface = Color(245, 235, 255, 255),
    inverseOnSurface = Color(36, 22, 58, 255),
    inversePrimary = Color(120, 55, 200, 255),

    // Structure — Borde / Resaltado
    outline = Color(88, 45, 150, 255),
    outlineVariant = Color(70, 38, 118, 255),
)

private val LightColorScheme = lightColorScheme(
    // Same hues as the PC palette, adapted to a light surface
    primary = Color(130, 60, 200, 255),
    onPrimary = Color(255, 255, 255, 255),
    primaryContainer = Color(235, 215, 255, 255),
    onPrimaryContainer = Color(40, 20, 70, 255),
    secondary = Color(200, 100, 160, 255),
    onSecondary = Color(255, 255, 255, 255),
    secondaryContainer = Color(255, 225, 240, 255),
    onSecondaryContainer = Color(70, 20, 55, 255),
    tertiary = Color(88, 45, 150, 255),
    onTertiary = Color(255, 255, 255, 255),

    background = Color(247, 243, 252, 255),
    onBackground = Color(30, 20, 48, 255),
    surface = Color(250, 247, 254, 255),
    onSurface = Color(30, 20, 48, 255),

    surfaceVariant = Color(232, 224, 244, 255),
    onSurfaceVariant = Color(90, 70, 120, 255),
    surfaceContainerLowest = Color(255, 255, 255, 255),
    surfaceContainerLow = Color(249, 246, 253, 255),
    surfaceContainer = Color(244, 240, 250, 255),
    surfaceContainerHigh = Color(238, 232, 248, 255),
    surfaceContainerHighest = Color(232, 224, 244, 255),
    surfaceBright = Color(252, 250, 255, 255),
    surfaceDim = Color(220, 212, 234, 255),
    inverseSurface = Color(30, 20, 48, 255),
    inverseOnSurface = Color(247, 243, 252, 255),
    inversePrimary = Color(220, 180, 255, 255),

    outline = Color(88, 45, 150, 255),
    outlineVariant = Color(205, 190, 225, 255),
)

@Composable
fun GatoClientTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
