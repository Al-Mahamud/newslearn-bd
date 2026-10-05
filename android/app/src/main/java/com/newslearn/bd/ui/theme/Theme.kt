package com.newslearn.bd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF0B6E4F)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEFE0),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFE3B0),
    onSecondaryContainer = Color(0xFF2B1800),
    tertiary = Color(0xFFB3261E),
    background = Color(0xFFFBFDF9),
    surface = Color(0xFFFBFDF9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD9B6),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFCFEFE0),
    secondary = Color(0xFFF5C26B),
    secondaryContainer = Color(0xFF5C3F00),
    onSecondaryContainer = Color(0xFFFFE3B0),
    tertiary = Color(0xFFF2B8B5),
)

@Composable
fun NewsLearnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
