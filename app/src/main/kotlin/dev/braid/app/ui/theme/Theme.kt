package dev.braid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.braid.crdt.ReplicaId

private val Light = lightColorScheme(
    primary = Color(0xFF3B4FA8), secondary = Color(0xFF2E7D62), tertiary = Color(0xFFB4632A),
    background = Color(0xFFFBF7F2), surface = Color(0xFFFBF7F2),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB8C4FF), secondary = Color(0xFF7FD1AE), tertiary = Color(0xFFF2A65A),
    background = Color(0xFF14161B), surface = Color(0xFF14161B),
)

@Composable
fun BraidTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Deterministic, well-separated color per collaborator: the same person has the same cursor
 * color on every device, without coordinating. Hue from the replica id via golden-ratio
 * spacing keeps neighbours distinct.
 */
fun ReplicaId.color(): Color {
    val hue = ((raw.toULong() % 1000uL).toFloat() * 0.618034f % 1f) * 360f
    return Color.hsv(hue, 0.62f, 0.86f)
}

val NoteColors: Map<String, Color> = linkedMapOf(
    "default" to Color.Transparent,
    "sun" to Color(0x55F2C14E),
    "mint" to Color(0x557FD1AE),
    "sky" to Color(0x5579A8E8),
    "rose" to Color(0x55E88AA4),
    "lilac" to Color(0x55B39DDB),
)
