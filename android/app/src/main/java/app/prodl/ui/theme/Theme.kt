package app.prodl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * AMOLED palette shared with the web/desktop SPA, so the three shells look like
 * one product: near-black background, slightly lifted cards, violet/indigo
 * accent, emerald for "ready".
 */
object ProDLColors {
    val Background = Color(0xFF0B0D12)
    val Card = Color(0xFF141822)
    val CardHigh = Color(0xFF1B2130)
    val Primary = Color(0xFF7C3AED)
    val PrimaryDim = Color(0xFF2A1B4D)
    val Indigo = Color(0xFF6366F1)
    val Ready = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFF87171)
    val Text = Color(0xFFE7E9EE)
    val TextDim = Color(0xFF9AA3B2)
    val Outline = Color(0xFF2A3243)
}

private val ProDLDarkScheme = darkColorScheme(
    primary = ProDLColors.Primary,
    onPrimary = Color.White,
    primaryContainer = ProDLColors.PrimaryDim,
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = ProDLColors.Indigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF232A45),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = ProDLColors.Ready,
    onTertiary = Color(0xFF04150E),
    tertiaryContainer = Color(0xFF0C2E22),
    onTertiaryContainer = Color(0xFFB9F5DC),
    background = ProDLColors.Background,
    onBackground = ProDLColors.Text,
    surface = ProDLColors.Card,
    onSurface = ProDLColors.Text,
    surfaceVariant = ProDLColors.CardHigh,
    onSurfaceVariant = ProDLColors.TextDim,
    surfaceContainer = ProDLColors.Card,
    surfaceContainerHigh = ProDLColors.CardHigh,
    surfaceContainerHighest = ProDLColors.CardHigh,
    error = ProDLColors.Danger,
    onError = Color(0xFF2A0A0A),
    outline = ProDLColors.Outline,
    outlineVariant = Color(0xFF212838),
    scrim = Color.Black,
)

private val ProDLTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        bodySmall = base.bodySmall.copy(lineHeight = 16.sp),
    )
}

/** Only the AMOLED dark scheme exists: the app is dark-only by design. */
@Composable
fun ProDLTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ProDLDarkScheme,
        typography = ProDLTypography,
        content = content,
    )
}

/** Mono style used for engine versions and task ids. */
val MonoStyle: TextStyle = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 12.sp,
)
