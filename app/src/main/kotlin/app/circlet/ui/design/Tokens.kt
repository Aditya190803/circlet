package app.circlet.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.R
import app.circlet.core.SleepStage

/**
 * Every colour the app draws with. Text on [Card] or the background uses [White], [Dim] or
 * [Faint]; [Ghost] and [Track] are for outlines and tracks only because they fail text contrast.
 */
/** Recovery scores from this value up are green; from [RECOVERY_MID_FROM] up to it, yellow; below, red. */
const val RECOVERY_HIGH_FROM = 67
const val RECOVERY_MID_FROM = 34

object Palette {
    val BgTop = Color(0xFF283339)
    val BgBottom = Color(0xFF101518)
    val Card = Color(0xFF1A2227)
    val CardRaised = Color(0xFF222C32)
    val CardLine = Color(0xFF27333A)
    val Track = Color(0x1FFFFFFF)
    val Scrim = Color(0xB3080B0D)
    val White = Color.White
    val Dim = Color(0xC2FFFFFF)
    val Faint = Color(0x8CFFFFFF)
    val Ghost = Color(0x40FFFFFF)
    val Strain = Color(0xFF0093E7)
    val Sleep = Color(0xFF7BA1BB)
    val RecoveryHigh = Color(0xFF3FD26B)
    val RecoveryMid = Color(0xFFF2CB3A)
    val RecoveryLow = Color(0xFFF0475B)
    val RecoveryNone = Color(0xFF597483)
    val Teal = Color(0xFF3CCB9C)

    /** [RecoveryLow] lifted until small text passes 4.5:1 on [Card]. */
    val Danger = Color(0xFFFF5C73)
    val Warn = RecoveryMid
    val Awake = Color(0xFF969696)
    val Light = Color(0xFF7BA1BB)
    val Deep = Color(0xFFFA95FA)
    val Rem = Color(0xFF9E7BFF)
    val Zones = listOf(
        Color(0xFF597483), Color(0xFFADC2CD), Color(0xFF479AC2),
        Color(0xFFF2CB3A), Color(0xFFF2A65E), Color(0xFFEE6B3B),
    )
    val StressLow = Sleep
    val StressMid = RecoveryMid
    val StressHigh = Color(0xFFEE6B3B)

    val Background = Brush.verticalGradient(listOf(BgTop, BgBottom))

    fun recovery(value: Int?) = when {
        value == null -> RecoveryNone
        value >= RECOVERY_HIGH_FROM -> RecoveryHigh
        value >= RECOVERY_MID_FROM -> RecoveryMid
        else -> RecoveryLow
    }

    fun stage(stage: SleepStage) = when (stage) {
        SleepStage.AWAKE -> Awake
        SleepStage.LIGHT -> Light
        SleepStage.DEEP -> Deep
        SleepStage.REM -> Rem
    }

    fun stress(score: Double) = when {
        score >= 67 -> StressHigh
        score >= 34 -> StressMid
        else -> StressLow
    }
}

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal inset of scrolling screens, and the gap between their cards. */
    val screen = 16.dp
    val cardGap = 12.dp

    /** Horizontal inset of onboarding and other full-page text. */
    val page = 24.dp

    /** Smallest touch target. */
    val touch = 48.dp
}

object Shapes {
    val card = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(10.dp)
    val chip = RoundedCornerShape(8.dp)
    val tile = RoundedCornerShape(6.dp)
    val bar = RoundedCornerShape(3.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val pill = CircleShape
}

/** Durations in milliseconds. Screens fade and rise in; they do not slide across. */
object Motion {
    const val ENTER = 240
    const val RISE = 280
    const val EXIT = 120
    const val RING = 1100
    const val STEP = 320
}

val Barlow = FontFamily(
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

/** Sentence-case names for rows, captions and buttons, never under 11 sp. Hierarchy comes from size and colour, not weight or capitals. */
fun label(size: TextUnit = 13.sp, color: Color = Palette.Dim) =
    TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = maxOf(size.value, 11f).sp, letterSpacing = 0.2.sp, color = color)

/** Tabular numerals so values do not shift as they change. */
fun number(size: TextUnit, color: Color = Palette.White) =
    TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = size, color = color, fontFeatureSettings = "tnum")

fun body(size: TextUnit = 15.sp, color: Color = Palette.Dim) =
    TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Medium, fontSize = size, color = color, lineHeight = size * 1.4)

/** Only display sizes tighten their tracking; small titles keep the face's own spacing. */
fun title(size: TextUnit = 30.sp, color: Color = Palette.White) =
    TextStyle(
        fontFamily = Barlow,
        fontWeight = if (size.value >= 22f) FontWeight.SemiBold else FontWeight.Medium,
        fontSize = size,
        color = color,
        lineHeight = size * 1.12,
        letterSpacing = if (size.value >= 24f) (-0.2).sp else 0.sp,
    )

@Composable
fun CircletTheme(timeFormat: TimeFormat, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.White,
            surface = Palette.Card,
            background = Palette.BgBottom,
            onSurface = Palette.White,
            onBackground = Palette.White,
        ),
    ) {
        CompositionLocalProvider(LocalTimeFormat provides timeFormat, content = content)
    }
}
