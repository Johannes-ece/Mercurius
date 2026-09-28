package de.jvg.mercurius.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import de.jvg.mercurius.core.TopUpReason

/**
 * Chart colors. Series hues come from the validated categorical palette (light and dark steps
 * picked separately); status hues are reserved for state and always ship with an icon and label.
 */
@Immutable
internal data class ChartColors(
    val series: List<Color>,
    val good: Color,
    val warning: Color,
    val critical: Color,
    val grid: Color,
    val axisText: Color,
    val surface: Color,
    val tooltipBackground: Color,
    val tooltipText: Color,
)

private val SERIES_LIGHT = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100))
private val SERIES_DARK = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500))

/** [surface] must be the color the charts sit on, since gaps and rings are painted in it. */
@Composable
internal fun rememberChartColors(surface: Color): ChartColors {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    return remember(dark, surface, scheme) {
        ChartColors(
            series = if (dark) SERIES_DARK else SERIES_LIGHT,
            good = Color(0xFF0CA30C),
            warning = Color(0xFFFAB219),
            critical = Color(0xFFD03B3B),
            grid = scheme.outlineVariant.copy(alpha = 0.7f),
            axisText = scheme.onSurfaceVariant,
            surface = surface,
            tooltipBackground = scheme.inverseSurface,
            tooltipText = scheme.inverseOnSurface,
        )
    }
}

/** Fixed slot per reason, so a reason keeps its color whatever the data looks like. */
internal fun ChartColors.reasonColor(reason: TopUpReason): Color = series[reason.ordinal]

internal fun reasonLabel(reason: TopUpReason) = when (reason) {
    TopUpReason.PROACTIVE -> "Proactive"
    TopUpReason.O2_SMS -> "O2 SMS"
    TopUpReason.SLOW -> "Slow"
    TopUpReason.MANUAL -> "Manual"
}

@Composable
internal fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) {
                    drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Centered note shown over an empty chart frame. */
@Composable
internal fun EmptyChartNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bar with a 4dp rounded data end and a square baseline. */
internal fun DrawScope.drawBar(color: Color, left: Float, top: Float, width: Float, bottom: Float, roundTop: Boolean = true) {
    val height = bottom - top
    if (height <= 0f || width <= 0f) return
    val r = if (roundTop) minOf(4.dp.toPx(), width / 2, height) else 0f
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, left + width, bottom),
                topLeft = CornerRadius(r),
                topRight = CornerRadius(r),
                bottomLeft = CornerRadius.Zero,
                bottomRight = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

/** Hairline horizontal gridline. */
internal fun DrawScope.gridLine(colors: ChartColors, y: Float, left: Float, right: Float) {
    drawLine(colors.grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
}

/** Draws [text] with its right edge at [right], vertically centered on [centerY]. */
internal fun DrawScope.axisLabelRight(measurer: TextMeasurer, text: String, style: TextStyle, right: Float, centerY: Float) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(right - layout.size.width, centerY - layout.size.height / 2f))
}

/** Draws [text] centered horizontally on [centerX], clamped inside the canvas. */
internal fun DrawScope.labelCentered(measurer: TextMeasurer, text: String, style: TextStyle, centerX: Float, top: Float) {
    val layout = measurer.measure(text, style)
    val x = (centerX - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, top))
}

/** Inverse-surface tooltip pill anchored above [anchor], kept inside the canvas. */
internal fun DrawScope.tooltip(measurer: TextMeasurer, text: String, style: TextStyle, colors: ChartColors, anchor: Offset) {
    val layout = measurer.measure(text, style.copy(color = colors.tooltipText))
    val padH = 8.dp.toPx()
    val padV = 4.dp.toPx()
    val w = layout.size.width + padH * 2
    val h = layout.size.height + padV * 2
    val x = (anchor.x - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val y = (anchor.y - h - 6.dp.toPx()).coerceAtLeast(0f)
    drawRoundRect(colors.tooltipBackground, Offset(x, y), Size(w, h), CornerRadius(6.dp.toPx()))
    drawText(layout, topLeft = Offset(x + padH, y + padV))
}
