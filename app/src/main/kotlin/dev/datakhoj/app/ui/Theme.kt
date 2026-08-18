/*
 * DataKhoj — a personal, unrestricted universal data collector.
 * Copyright (C) 2026 soobujmiah
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details: <https://www.gnu.org/licenses/>.
 *
 * "DataKhoj" and its logo are trademarks of the copyright holder and are NOT
 * licensed under the AGPL. Forks must use their own name and branding.
 */

package dev.datakhoj.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * DataKhoj identity — "Deep Jade & Saffron".
 *
 * Material 3 (Apache 2.0) used as designed for third-party apps, with an
 * independent palette so nothing reads as a Google product. No Product Sans,
 * no Google marks, no four-colour trade dress. See design/BRAND.md.
 */
val Jade        = Color(0xFF0B6E5F)
val JadeHi      = Color(0xFF0E8C78)
val JadeDim     = Color(0xFFE6F4F1)
val Saffron     = Color(0xFFE08A1E)
val Ink         = Color(0xFF111917)
val Ink2        = Color(0xFF5A6B66)
val Line        = Color(0xFFDDE5E2)
val Bg          = Color(0xFFFBFCFC)
val Surface     = Color(0xFFFFFFFF)
val Ok          = Color(0xFF1F7A4C)
val Warn        = Color(0xFFB4690E)
val Err         = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Jade,          onPrimary = Color.White,
    primaryContainer = JadeDim, onPrimaryContainer = Color(0xFF07332C),
    secondary = Saffron,     onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDF0DC), onSecondaryContainer = Color(0xFF4A2D02),
    background = Bg,         onBackground = Ink,
    surface = Surface,       onSurface = Ink,
    surfaceVariant = Color(0xFFF2F6F5), onSurfaceVariant = Ink2,
    outline = Line,          error = Err, onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD4C3), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B5145), onPrimaryContainer = Color(0xFF9BF0DE),
    secondary = Color(0xFFF0BC77), onSecondary = Color(0xFF442B00),
    background = Color(0xFF0C1210), onBackground = Color(0xFFE1E5E3),
    surface = Color(0xFF131B19),    onSurface = Color(0xFFE1E5E3),
    surfaceVariant = Color(0xFF1C2523), onSurfaceVariant = Color(0xFFA9B8B4),
    outline = Color(0xFF3A4643), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

@Composable
fun DataKhojTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            w.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
