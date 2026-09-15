package com.autosend.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** AutoSend 的冷调浅色视觉基线，保持与到点啦一致的留白、圆角和层级。 */
object AutoSendColors {
    val background = Color(0xFFF1F2FF)
    val ink = Color(0xFF1D2A50)
    val inkSoft = Color(0xFF40548B)
    val blue = Color(0xFF4868AE)
    val blueTint = Color(0xFFDDE5FF)
    val amber = Color(0xFFB9C8FF)
    val muted = Color(0xFF667399)
    val line = Color(0xFFE3E6F5)
    val disabled = Color(0xFFF0F1F8)
    val hero = Color(0xFFB7C8FA)
    val warningTint = Color(0xFFFFF1D2)
    val warning = Color(0xFF94651C)
    val success = Color(0xFF3F806C)
    val successTint = Color(0xFFDDF3EA)
    val error = Color(0xFFB45467)
    val errorTint = Color(0xFFFFE3E8)
}

private val AutoSendLightColors = lightColorScheme(
    primary = AutoSendColors.blue,
    onPrimary = Color.White,
    primaryContainer = AutoSendColors.blueTint,
    onPrimaryContainer = AutoSendColors.ink,
    secondary = AutoSendColors.amber,
    onSecondary = AutoSendColors.ink,
    secondaryContainer = AutoSendColors.blueTint,
    onSecondaryContainer = AutoSendColors.ink,
    background = AutoSendColors.background,
    onBackground = AutoSendColors.ink,
    surface = Color.White,
    onSurface = AutoSendColors.ink,
    surfaceVariant = Color(0xFFE9ECF8),
    onSurfaceVariant = AutoSendColors.muted,
    outline = AutoSendColors.line,
    error = AutoSendColors.error,
    errorContainer = AutoSendColors.errorTint,
    onError = Color.White,
    onErrorContainer = AutoSendColors.error
)

private val AutoSendTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif)
    )
}

private val AutoSendShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AutoSendTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AutoSendLightColors,
        typography = AutoSendTypography,
        shapes = AutoSendShapes,
        content = content
    )
}
