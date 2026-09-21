package com.chengjieli.medication.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Define the container roles too, so cards and navigation share the app palette.
private val LightColors = lightColorScheme(
    primary = Color(0xFF007F89),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF7E8),
    onPrimaryContainer = Color(0xFF063A4A),
    inversePrimary = Color(0xFF8EDCC6),
    secondary = Color(0xFFAC4D33),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEDDF),
    onSecondaryContainer = Color(0xFF733319),
    tertiary = Color(0xFF81600A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBAA),
    onTertiaryContainer = Color(0xFF60480C),
    background = Color(0xFFFCF9F1),
    onBackground = Color(0xFF063A4A),
    surface = Color(0xFFFCF9F1),
    onSurface = Color(0xFF063A4A),
    surfaceVariant = Color(0xFFF1F3EC),
    onSurfaceVariant = Color(0xFF5F6A7B),
    surfaceTint = Color(0xFF007F89),
    inverseSurface = Color(0xFF29463D),
    inverseOnSurface = Color(0xFFF5FBF5),
    outline = Color(0xFF72838C),
    outlineVariant = Color(0xFFE0E6E3),
    scrim = Color(0xFF102A22),
    error = Color(0xFFB63639),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF6F151B),
    surfaceDim = Color(0xFFDFE4DA),
    surfaceBright = Color(0xFFFCF9F1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFDF8),
    surfaceContainer = Color(0xFFF8F6F0),
    surfaceContainerHigh = Color(0xFFF2F0E8),
    surfaceContainerHighest = Color(0xFFECEFE8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EDCC6),
    onPrimary = Color(0xFF003D34),
    primaryContainer = Color(0xFF175C50),
    onPrimaryContainer = Color(0xFFBDEEE0),
    inversePrimary = Color(0xFF087D72),
    secondary = Color(0xFFFFB694),
    onSecondary = Color(0xFF57200F),
    secondaryContainer = Color(0xFF783D28),
    onSecondaryContainer = Color(0xFFFFDFCC),
    tertiary = Color(0xFFEBCB71),
    onTertiary = Color(0xFF402F00),
    tertiaryContainer = Color(0xFF61490A),
    onTertiaryContainer = Color(0xFFFFEBAA),
    background = Color(0xFF13231F),
    onBackground = Color(0xFFE4F0E6),
    surface = Color(0xFF13231F),
    onSurface = Color(0xFFE4F0E6),
    surfaceVariant = Color(0xFF3C5147),
    onSurfaceVariant = Color(0xFFBFCEC1),
    surfaceTint = Color(0xFF8EDCC6),
    inverseSurface = Color(0xFFE4F0E6),
    inverseOnSurface = Color(0xFF29463D),
    outline = Color(0xFF899E90),
    outlineVariant = Color(0xFF3C5147),
    scrim = Color.Black,
    error = Color(0xFFFFB4AC),
    onError = Color(0xFF69000B),
    errorContainer = Color(0xFF8F242A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF101E19),
    surfaceBright = Color(0xFF34453C),
    surfaceContainerLowest = Color(0xFF0D1915),
    surfaceContainerLow = Color(0xFF192B23),
    surfaceContainer = Color(0xFF1D3028),
    surfaceContainerHigh = Color(0xFF273B31),
    surfaceContainerHighest = Color(0xFF32483B),
)

internal val LocalSeniorMode = staticCompositionLocalOf { false }

private val StandardTypography = Typography().let { base -> base.copy(
    headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = base.titleLarge.copy(fontSize = 23.sp, lineHeight = 31.sp),
    titleMedium = base.titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodySmall = base.bodySmall.copy(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
) }

// Use sp throughout so Android's font-size setting still applies on top of this mode.
private val SeniorTypography = StandardTypography.copy(
    displayLarge = StandardTypography.displayLarge.copy(fontSize = 60.sp, lineHeight = 68.sp),
    displayMedium = StandardTypography.displayMedium.copy(fontSize = 48.sp, lineHeight = 56.sp),
    displaySmall = StandardTypography.displaySmall.copy(fontSize = 40.sp, lineHeight = 48.sp),
    headlineLarge = StandardTypography.headlineLarge.copy(fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = StandardTypography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 40.sp),
    headlineSmall = StandardTypography.headlineSmall.copy(fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = StandardTypography.titleLarge.copy(fontSize = 28.sp, lineHeight = 36.sp),
    titleMedium = StandardTypography.titleMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
    titleSmall = StandardTypography.titleSmall.copy(fontSize = 22.sp, lineHeight = 30.sp),
    bodyLarge = StandardTypography.bodyLarge.copy(fontSize = 22.sp, lineHeight = 32.sp),
    bodyMedium = StandardTypography.bodyMedium.copy(fontSize = 20.sp, lineHeight = 30.sp),
    bodySmall = StandardTypography.bodySmall.copy(fontSize = 18.sp, lineHeight = 28.sp),
    labelLarge = StandardTypography.labelLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    labelMedium = StandardTypography.labelMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
    labelSmall = StandardTypography.labelSmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
)

private val SeniorLightColors = LightColors.copy(
    primary = Color(0xFF006B77),
    onPrimaryContainer = Color(0xFF063A4A),
    secondary = Color(0xFF84331E),
    onSecondaryContainer = Color(0xFF45200D),
    tertiary = Color(0xFF614700),
    onTertiaryContainer = Color(0xFF3C2B00),
    onBackground = Color(0xFF063A4A),
    onSurface = Color(0xFF063A4A),
    onSurfaceVariant = Color(0xFF405364),
    outline = Color(0xFF496054),
    error = Color(0xFF952024),
)

private val SeniorDarkColors = DarkColors.copy(
    primary = Color(0xFFB3F5DE),
    onPrimary = Color(0xFF00291F),
    onPrimaryContainer = Color(0xFFE0FFF0),
    secondary = Color(0xFFFFD4BD),
    onSecondaryContainer = Color(0xFFFFEFE4),
    tertiary = Color(0xFFFFE49B),
    onTertiaryContainer = Color(0xFFFFF3C5),
    onBackground = Color(0xFFF4FFF5),
    onSurface = Color(0xFFF4FFF5),
    onSurfaceVariant = Color(0xFFDEEFE0),
    outline = Color(0xFFB6CDBB),
    error = Color(0xFFFFC7C1),
)

@Composable
internal fun MedicationTheme(seniorMode: Boolean = false, content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalSeniorMode provides seniorMode,
        LocalMinimumInteractiveComponentSize provides if (seniorMode) 60.dp else 48.dp,
    ) {
        MaterialTheme(
            colorScheme = when {
                seniorMode && darkTheme -> SeniorDarkColors
                seniorMode -> SeniorLightColors
                darkTheme -> DarkColors
                else -> LightColors
            },
            typography = if (seniorMode) SeniorTypography else StandardTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
