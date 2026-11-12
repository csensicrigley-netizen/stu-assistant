package com.stuassistant.app.nativeapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified 24×24 rounded vector icon family shared across Native Compose surfaces.
 * Paths are parsed once per icon name and reused across frames with zero draw-time allocations.
 */
object NativeIcons {
    private val cache = ConcurrentHashMap<String, Path>()

    internal val svgPaths: Map<String, String> = mapOf(
        "today" to "M12 8.3a3.7 3.7 0 1 0 0 7.4 3.7 3.7 0 0 0 0-7.4ZM12 3.2v1.9M12 18.9v1.9M3.2 12h1.9M18.9 12h1.9M5.8 5.8l1.25 1.25M16.95 16.95l1.25 1.25M18.2 5.8l-1.25 1.25M7.05 16.95 5.8 18.2",
        "items" to "M7.2 4.2h9.6A2.8 2.8 0 0 1 19.6 7v10a2.8 2.8 0 0 1-2.8 2.8H7.2A2.8 2.8 0 0 1 4.4 17V7a2.8 2.8 0 0 1 2.8-2.8ZM8 10.3l1.7 1.7 3.1-3.2M14.8 10.4h1.6M8.2 15h8.2",
        "plan" to "M7.2 4.2h9.6A2.8 2.8 0 0 1 19.6 7v10a2.8 2.8 0 0 1-2.8 2.8H7.2A2.8 2.8 0 0 1 4.4 17V7a2.8 2.8 0 0 1 2.8-2.8ZM8 10.3l1.7 1.7 3.1-3.2M14.8 10.4h1.6M8.2 15h8.2",
        "week" to "M6.8 4.8h10.4A2.6 2.6 0 0 1 19.8 7.4v10a2.6 2.6 0 0 1-2.6 2.6H6.8a2.6 2.6 0 0 1-2.6-2.6v-10a2.6 2.6 0 0 1 2.6-2.6ZM8.5 3.3v2.8M15.5 3.3v2.8M4.4 9.5h15.2M8 13h3.2M13 13h3M8 16.2h5.2",
        "study" to "M4.2 6.4c2.5-1.1 5.2-.9 7.8.7 2.6-1.6 5.3-1.8 7.8-.7v11.8c-2.5-1.1-5.2-.9-7.8.7-2.6-1.6-5.3-1.8-7.8-.7V6.4ZM12 7.1v11.8M7.3 10.2h2.2M14.5 10.2h2.2",
        "academic" to "M3.2 9.6 12 5.2l8.8 4.4L12 14 3.2 9.6ZM6.8 11.5v4.2c0 1.6 2.3 3 5.2 3s5.2-1.4 5.2-3v-4.2M20.8 9.8v5",
        "campus" to "M3.8 19.8h16.4M5.6 19.8v-8.4M18.4 19.8v-8.4M3.6 11.6 11.2 5.3a1.3 1.3 0 0 1 1.6 0l7.6 6.3M9.7 19.8v-3.7a2.3 2.3 0 0 1 4.6 0v3.7M11.95 9.4h0.1",
        "me" to "M12 11.8a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM5.4 19.6c.9-3.2 3.5-5 6.6-5s5.7 1.8 6.6 5",
        "chevron-left" to "M14.5 6.2 8.7 12l5.8 5.8",
        "chevron-right" to "M9.5 6.2 15.3 12l-5.8 5.8",
        "chevron" to "M9.5 6.2 15.3 12l-5.8 5.8",
        "chevron-down" to "M6.2 9.5 12 15.3l5.8-5.8",
        "chevron-up" to "M6.2 14.5 12 8.7l5.8 5.8",
        "arrow-right" to "M5 12h13.5M13.2 6.6 18.6 12l-5.4 5.4",
        "arrow-up" to "M12 18.8V5.4M6.8 10.4 12 5.2l5.2 5.2",
        "send" to "M12 18.8V5.4M6.8 10.4 12 5.2l5.2 5.2",
        "plus" to "M12 5.5v13M5.5 12h13",
        "more" to "M6.2 12a1 1 0 1 0 2 0 1 1 0 0 0-2 0ZM11 12a1 1 0 1 0 2 0 1 1 0 0 0-2 0ZM15.8 12a1 1 0 1 0 2 0 1 1 0 0 0-2 0Z",
        "external" to "M13.8 5H19v5.2M19 5l-7.8 7.8M17.2 13.8v3.6a1.8 1.8 0 0 1-1.8 1.8H6.8A1.8 1.8 0 0 1 5 17.4V8.8A1.8 1.8 0 0 1 6.8 7h3.6",
        "search" to "M10.8 17.4a6.6 6.6 0 1 0 0-13.2 6.6 6.6 0 0 0 0 13.2ZM15.8 15.8l4 4",
        "check" to "M5.2 12.6l4.3 4.3L18.8 7.6",
        "close" to "M6.8 6.8l10.4 10.4M17.2 6.8 6.8 17.2",
        "pin" to "M12 20.6s6.2-5.3 6.2-10.5a6.2 6.2 0 0 0-12.4 0c0 5.2 6.2 10.5 6.2 10.5ZM12 12.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4Z",
        "person" to "M12 11.4a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4ZM6.2 19.4c.7-2.9 3-4.5 5.8-4.5s5.1 1.6 5.8 4.5",
        "bolt" to "M13.2 3.6 6.2 13h5.1l-.8 7.4L17.8 10.8h-5.1l.5-7.2Z",
        "bus" to "M6.4 17.4V7a2.8 2.8 0 0 1 2.8-2.8h5.6A2.8 2.8 0 0 1 17.6 7v10.4M6.4 11.8h11.2M6.4 17.4h11.2M8.4 17.4v2.2M15.6 17.4v2.2M8.95 14.6h0.1M14.95 14.6h0.1",
        "book" to "M6 4.6h10.2A1.8 1.8 0 0 1 18 6.4v13H7.8A1.8 1.8 0 0 1 6 17.6V4.6ZM6 17.6a1.8 1.8 0 0 1 1.8-1.8H18M9.5 8.6h5",
        "calendar" to "M6.8 4.8h10.4A2.6 2.6 0 0 1 19.8 7.4v10a2.6 2.6 0 0 1-2.6 2.6H6.8a2.6 2.6 0 0 1-2.6-2.6v-10a2.6 2.6 0 0 1 2.6-2.6ZM4.4 9.6h15.2M8.5 3.4v2.8M15.5 3.4v2.8",
        "bell" to "M6.6 16.4V11a5.4 5.4 0 0 1 10.8 0v5.4l1.4 1.4H5.2l1.4-1.4ZM10.2 19.8h3.6",
        "refresh" to "M18.8 12a6.8 6.8 0 1 1-2-4.8M18.8 4.6v3.4h-3.4",
        "trash" to "M4.8 6.8h14.4M9.5 6.8V5.2a1.4 1.4 0 0 1 1.4-1.4h2.2a1.4 1.4 0 0 1 1.4 1.4v1.6M6.8 6.8l.7 11.1a2.1 2.1 0 0 0 2.1 1.9h4.8a2.1 2.1 0 0 0 2.1-1.9l.7-11.1M10.3 10.8v5.2M13.7 10.8v5.2",
        "palette" to "M12 3.8a8.2 8.2 0 0 0 0 16.4h1.3a2.1 2.1 0 0 0 1.5-3.6 1.6 1.6 0 0 1 1.2-2.7h1.4A2.8 2.8 0 0 0 20.2 11 7.8 7.8 0 0 0 12 3.8ZM8.25 10.2h0.1M10.75 7.6h0.1M14.35 7.8h0.1",
        "spark" to "M12 3.6c.55 4.2 2.85 6.5 7.05 7.05-4.2.55-6.5 2.85-7.05 7.05-.55-4.2-2.85-6.5-7.05-7.05 4.2-.55 6.5-2.85 7.05-7.05Z",
        "clock" to "M12 20.2a8.2 8.2 0 1 0 0-16.4 8.2 8.2 0 0 0 0 16.4ZM12 7.6V12l2.9 1.9",
        "info" to "M12 20.2a8.2 8.2 0 1 0 0-16.4 8.2 8.2 0 0 0 0 16.4ZM12 11.2v5M11.95 8h0.1",
        "shield" to "M12 3.6 5.2 6.2v5.2c0 4.2 2.9 7.5 6.8 8.9 3.9-1.4 6.8-4.7 6.8-8.9V6.2L12 3.6ZM9.2 12l2.1 2.1 3.9-3.9",
        "locate" to "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM12 3.5v2.3M12 18.2v2.3M3.5 12h2.3M18.2 12h2.3",
        "chat" to "M6.4 17.6 4 19.6V7.2A2.6 2.6 0 0 1 6.6 4.6h10.8A2.6 2.6 0 0 1 20 7.2v7.8a2.6 2.6 0 0 1-2.6 2.6H6.4ZM8.6 9.8h6.8M8.6 13.2h4.4",
        "grid" to "M4.6 5.8A1.2 1.2 0 0 1 5.8 4.6h3.8a1.2 1.2 0 0 1 1.2 1.2v3.8a1.2 1.2 0 0 1-1.2 1.2H5.8a1.2 1.2 0 0 1-1.2-1.2V5.8ZM13.2 5.8a1.2 1.2 0 0 1 1.2-1.2h3.8a1.2 1.2 0 0 1 1.2 1.2v3.8a1.2 1.2 0 0 1-1.2 1.2h-3.8a1.2 1.2 0 0 1-1.2-1.2V5.8ZM4.6 14.4a1.2 1.2 0 0 1 1.2-1.2h3.8a1.2 1.2 0 0 1 1.2 1.2v3.8a1.2 1.2 0 0 1-1.2 1.2H5.8a1.2 1.2 0 0 1-1.2-1.2v-3.8ZM13.2 14.4a1.2 1.2 0 0 1 1.2-1.2h3.8a1.2 1.2 0 0 1 1.2 1.2v3.8a1.2 1.2 0 0 1-1.2 1.2h-3.8a1.2 1.2 0 0 1-1.2-1.2v-3.8Z",
        "wave" to "M4 12h2.4l1.9-4.4 3.4 8.8 2.4-6.2 1.6 1.8H20",
        "award" to "M12 14.6a5.4 5.4 0 1 0 0-10.8 5.4 5.4 0 0 0 0 10.8ZM9.2 13.8 7.8 20.2 12 18l4.2 2.2-1.4-6.4",
    )

    fun path(name: String): Path = cache.getOrPut(name) {
        val raw = svgPaths[name] ?: svgPaths["me"]!!
        PathParser().parsePathString(raw).toPath()
    }
}

@Composable
fun NativeIcon(
    name: String,
    color: Color,
    size: Dp = 20.dp,
    strokeWidth: Float = 1.8f,
    modifier: Modifier = Modifier,
) {
    val iconPath = remember(name) { NativeIcons.path(name) }
    val stroke = remember(strokeWidth) {
        Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) {
            drawPath(iconPath, color = color, style = stroke)
        }
    }
}
