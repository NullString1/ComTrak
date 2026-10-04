package com.comtrak.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Terracotta,
    onPrimary = WarmWhite,
    primaryContainer = TerracottaLight,
    onPrimaryContainer = ClayDark,
    secondary = Sage,
    onSecondary = WarmWhite,
    secondaryContainer = SageLight,
    onSecondaryContainer = ClayDark,
    tertiary = GlazeBlue,
    onTertiary = WarmWhite,
    background = WarmWhite,
    onBackground = ClayDark,
    surface = Sand,
    onSurface = ClayDark,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = ClayLight
)

private val DarkColorScheme = darkColorScheme(
    primary = TerracottaLight,
    onPrimary = ClayDark,
    primaryContainer = TerracottaDark,
    onPrimaryContainer = WarmWhite,
    secondary = SageLight,
    onSecondary = ClayDark,
    tertiary = GlazeBlueLight,
    onTertiary = ClayDark,
    background = ClayDark,
    onBackground = WarmWhite,
    surface = ClayMedium,
    onSurface = WarmWhite,
    surfaceVariant = ClayDark,
    onSurfaceVariant = Sand
)

@Composable
fun ComTrakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
