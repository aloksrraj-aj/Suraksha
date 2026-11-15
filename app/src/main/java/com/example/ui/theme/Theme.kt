package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SurakshaColorScheme = darkColorScheme(
    primary = CyanElectric,
    onPrimary = OnCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = BlueSky,
    onSecondary = OnCyan,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = OnBlueContainer,
    tertiary = StatusEstimated,
    onTertiary = OnCyan,
    background = NavyDeep,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = NavyDeep,
    surfaceContainerLow = NavySurface,
    surfaceContainer = NavySurfaceHigh,
    surfaceContainerHigh = NavySurfaceHighest,
    outline = NavyOutline,
    outlineVariant = NavyOutlineVariant,
    error = StatusCritical,
    onError = TextPrimary,
    errorContainer = CriticalContainer,
    onErrorContainer = OnCriticalContainer,
)

private val SurakshaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SurakshaTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SurakshaColorScheme,
        typography = SurakshaTypography,
        shapes = SurakshaShapes,
        content = content,
    )
}
