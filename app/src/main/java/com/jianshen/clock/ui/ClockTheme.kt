package com.jianshen.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF101217)
internal val Panel = Color(0xFF1C2029)
internal val Muted = Color(0xFF9CA5B5)
internal val Orange = Color(0xFFFF946C)
internal val Cream = Color(0xFFF6F1E9)
internal val Green = Color(0xFFB6DFC2)

@Composable internal fun ClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Orange, onPrimary = Ink,
            background = Ink, onBackground = Cream, surface = Panel, onSurface = Cream,
            surfaceVariant = Color(0xFF262B35), onSurfaceVariant = Muted,
            secondary = Green, outline = Color(0xFF414858), error = Color(0xFFFFB4AB)),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
        ),
        shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}

@Composable internal fun Tag(text: String, accent: Boolean = false) {
    Text(text, color = if (accent) Orange else Muted, fontSize = 12.sp,
        modifier = Modifier.background(if (accent) Orange.copy(alpha = .10f) else Color(0xFF272C36),
            RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable internal fun SectionHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
