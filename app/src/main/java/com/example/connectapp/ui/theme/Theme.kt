package com.example.connectapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.connectapp.data.settings.AppSettings

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Tertiary,
    error = ErrorRed
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    secondary = SecondaryDark,
    tertiary = TertiaryDark,
    error = ErrorRed
)

@Composable
fun ConnectAppTheme(
    settings: AppSettings = AppSettings.DEFAULT,
    // Material You: на Android 12+ берём системные цвета — приятнее.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // settings.darkTheme: true=dark, false=light, null=system.
    val darkTheme = settings.darkTheme ?: isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // safe-cast: Compose может рендериться вне Activity (ComposeView во фрагменте/preview).
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // statusBarColor выставлен темой (transparent), Compose сам рисует системные инсеты
            // через windowInsetsPadding — поэтому окрашивать window здесь не нужно (deprecated в API 35).
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
