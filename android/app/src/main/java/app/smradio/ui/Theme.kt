package app.smradio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Values come from design/tokens.json (skin "green")
val Bg = Color(0xFF050907)
val Panel = Color(0xFF0B120E)
val Panel2 = Color(0xFF101A14)
val TextMain = Color(0xFFECF5EE)
val Muted = Color(0xFFA8B7AD)
val Line = Color(0xFF1F2A23)
val Sand = Color(0xFFE5C18B)
val Lit = Color(0xFF67FF91)
val Dim = Color(0xFF1B4C28)
val Hot = Color(0xFFFFD84D)
val ErrorRed = Color(0xFFFF6B6B)

fun parseColor(hex: String, fallback: Color = Lit): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    fallback
}

@Composable
fun RetroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Panel,
            onBackground = TextMain,
            onSurface = TextMain,
            primary = Lit,
            onPrimary = Bg,
        ),
        content = content,
    )
}
