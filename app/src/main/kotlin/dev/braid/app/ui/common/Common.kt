package dev.braid.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.braid.app.ui.theme.color
import dev.braid.crdt.ReplicaId
import dev.braid.crdt.Timestamp
import java.text.DateFormat
import java.util.Date

@Composable
fun Avatar(replica: ReplicaId, name: String?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier.size(size).background(replica.color(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name?.firstOrNull() ?: '?').uppercase(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

fun Timestamp.relative(now: Long = System.currentTimeMillis()): String {
    val d = (now - millis).coerceAtLeast(0)
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}

fun Timestamp.absolute(): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
