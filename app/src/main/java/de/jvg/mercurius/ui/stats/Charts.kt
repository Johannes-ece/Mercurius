package de.jvg.mercurius.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import de.jvg.mercurius.core.TopUpReason
import kotlin.math.abs
import kotlin.math.log10

private val CHART_HEIGHT = 184.dp
private val AXIS_WIDTH = 52.dp
private val X_LABEL_HEIGHT = 20.dp
private val TOP_ROOM = 28.dp // space for the tooltip above the tallest mark
private val MAX_BAR = 24.dp

/** Mobile data per hour for the last 24 hours; the current hour is the accent, earlier hours recede. */
@Composable
internal fun HourlyUsageChart(bars: List<HourBar>, colors: ChartColors, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.axisText)
    val tipStyle = MaterialTheme.typography.labelMedium
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val maxBytes = bars.maxOfOrNull { it.bytes } ?: 0
    val top = niceCeil(maxOf(maxBytes.toDouble(), 100e6))
    val empty = maxBytes == 0L
    val total = bars.sumOf { it.bytes }

    Box(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Mobile data per hour, last 24 hours. ${formatBytes(total)} in total." }
                .pointerInput(bars) {
                    detectTapGestures { tap ->
                        val left = AXIS_WIDTH.toPx()
                        val slot = (size.width - left) / bars.size
                        val i = ((tap.x - left) / slot).toInt()
                        selected = if (i in bars.indices && i != selected) i else null
                    }
                },
        ) {
            val left = AXIS_WIDTH.toPx()
            val plotTop = TOP_ROOM.toPx()
            val plotBottom = size.height - X_LABEL_HEIGHT.toPx()
            val plotH = plotBottom - plotTop
            val gap = 8.dp.toPx()

            listOf(0.0, top / 2, top).forEach { v ->
                val y = plotBottom - (v / top * plotH).toFloat()
                gridLine(colors, y, left, size.width)
                axisLabelRight(measurer, formatAxisBytes(v), axisStyle, left - gap, y)
            }

            val slot = (size.width - left) / bars.size
            val barW = minOf(slot * 0.7f, MAX_BAR.toPx())
            bars.forEachIndexed { i, bar ->
                val cx = left + slot * i + slot / 2
                val h = (bar.bytes / top * plotH).toFloat()
                val accent = bar.current || i == selected
                val color = if (accent) colors.series[0] else colors.series[0].copy(alpha = 0.42f)
                drawBar(color, cx - barW / 2, plotBottom - h, barW, plotBottom)

                val hour = localHour(bar.start)
                val isLast = i == bars.lastIndex
                val collidesWithNow = bars.lastIndex - i < 3 && !isLast
                if (isLast) {
                    labelCentered(measurer, "now", axisStyle, cx, plotBottom + 4.dp.toPx())
                } else if (hour % 6 == 0 && !collidesWithNow) {
                    labelCentered(measurer, "%02d".format(hour), axisStyle, cx, plotBottom + 4.dp.toPx())
                }
            }

            selected?.let { i ->
                val bar = bars[i]
                val cx = left + slot * i + slot / 2
                val y = plotBottom - (bar.bytes / top * plotH).toFloat()
                tooltip(measurer, "${clockTime(bar.start)}  ${formatBytes(bar.bytes)}", tipStyle, colors, Offset(cx, y))
            }
        }
        if (empty) EmptyChartNote("No mobile data counted yet", Modifier.align(Alignment.Center))
    }
}

/**
 * Speed over the last 24 hours on a log scale, so a throttled 64 kbit/s and a 5G 300 Mbit/s are both
 * readable. Speed tests form the line; live-traffic readings are separate dots.
 */
@Composable
internal fun SpeedChart(points: List<SpeedPoint>, now: Long, throttledKbps: Long, colors: ChartColors, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.axisText)
    val tipStyle = MaterialTheme.typography.labelMedium
    var selected by remember(points) { mutableStateOf<SpeedPoint?>(null) }
    val start = now - 24 * HOUR_MS
    val maxKbps = points.maxOfOrNull { it.kbps } ?: 0
    val topKbps = if (maxKbps > 100_000) 1_000_000.0 else 100_000.0
    val minKbps = 32.0
    val lMin = log10(minKbps)
    val lMax = log10(topKbps)
    val ticks = listOf(100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0).filter { it <= topKbps }

    Column(modifier.fillMaxWidth()) {
        Legend(listOf("Speed test" to colors.series[0], "Live traffic" to colors.series[1]))
        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(top = 4.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        val last = points.lastOrNull()
                        contentDescription = if (last == null) "Speed chart, no measurements yet."
                        else "Speed over the last 24 hours. Latest ${formatSpeed(last.kbps)} at ${clockTime(last.at)}."
                    }
                    .pointerInput(points, now) {
                        detectTapGestures { tap ->
                            val left = AXIS_WIDTH.toPx()
                            val w = size.width - left
                            val nearest = points.minByOrNull { abs(left + (it.at - start).toFloat() / (24 * HOUR_MS) * w - tap.x) }
                            val nearestX = nearest?.let { left + (it.at - start).toFloat() / (24 * HOUR_MS) * w }
                            selected = if (nearest != null && abs(nearestX!! - tap.x) < 24.dp.toPx() && nearest != selected) nearest else null
                        }
                    },
            ) {
                val left = AXIS_WIDTH.toPx()
                val plotTop = TOP_ROOM.toPx()
                val plotBottom = size.height - X_LABEL_HEIGHT.toPx()
                val plotH = plotBottom - plotTop
                val plotW = size.width - left
                fun x(at: Long) = left + (at - start).toFloat() / (24 * HOUR_MS) * plotW
                fun y(kbps: Long): Float {
                    val l = log10(kbps.toDouble().coerceIn(minKbps, topKbps))
                    return plotBottom - ((l - lMin) / (lMax - lMin) * plotH).toFloat()
                }

                ticks.forEach { t ->
                    val ty = y(t.toLong())
                    gridLine(colors, ty, left, size.width)
                    axisLabelRight(measurer, formatSpeed(t.toLong()).replace(",0", ""), axisStyle, left - 8.dp.toPx(), ty)
                }

                // Reference line: below this, O2 has throttled the connection.
                val ry = y(throttledKbps)
                drawLine(
                    colors.critical.copy(alpha = 0.8f), Offset(left, ry), Offset(size.width, ry),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
                val label = measurer.measure("throttled", axisStyle)
                drawText(label, topLeft = Offset(size.width - label.size.width, ry + 2.dp.toPx()))

                // Hour labels every 6 local hours.
                var hourTick = start - start % HOUR_MS + HOUR_MS
                while (hourTick <= now) {
                    if (localHour(hourTick) % 6 == 0) {
                        labelCentered(measurer, clockTime(hourTick), axisStyle, x(hourTick), plotBottom + 4.dp.toPx())
                    }
                    hourTick += HOUR_MS
                }

                // Speed-test line, broken where there is more than an hour between tests.
                val probed = points.filter { it.probed }
                val line = Path()
                probed.forEachIndexed { i, p ->
                    val px = x(p.at)
                    val py = y(p.kbps)
                    if (i == 0 || p.at - probed[i - 1].at > HOUR_MS) line.moveTo(px, py) else line.lineTo(px, py)
                }
                drawPath(line, colors.series[0], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                val ring = 2.dp.toPx()
                val r = 4.dp.toPx()
                points.forEach { p ->
                    val c = Offset(x(p.at), y(p.kbps))
                    val color = if (p.probed) colors.series[0] else colors.series[1]
                    val radius = if (p == selected) r + 2.dp.toPx() else r
                    drawCircle(colors.surface, radius + ring, c)
                    drawCircle(color, radius, c)
                }

                selected?.let { p ->
                    val kind = if (p.probed) "speed test" else "live traffic"
                    tooltip(measurer, "${clockTime(p.at)}  ${formatSpeed(p.kbps)}  $kind", tipStyle, colors, Offset(x(p.at), y(p.kbps) - r))
                }
            }
            if (points.isEmpty()) EmptyChartNote("No speed measurements yet", Modifier.align(Alignment.Center))
        }
    }
}

/** WEITER top-ups per day for the last 7 days, stacked by reason in a fixed order. */
@Composable
internal fun TopUpChart(days: List<DayTopUps>, now: Long, colors: ChartColors, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.axisText)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val tipStyle = MaterialTheme.typography.labelMedium
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val maxTotal = days.maxOfOrNull { it.total } ?: 0
    val top = maxOf(niceCeil(maxTotal.toDouble()), 4.0)
    val reasons = TopUpReason.entries
    val today = startOfDay(now)

    Column(modifier.fillMaxWidth()) {
        Legend(reasons.map { reasonLabel(it) to colors.reasonColor(it) })
        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT).padding(top = 4.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "WEITER top-ups per day, last 7 days: " +
                            days.joinToString { "${weekdayShort(it.start)} ${it.total}" }
                    }
                    .pointerInput(days) {
                        detectTapGestures { tap ->
                            val left = AXIS_WIDTH.toPx()
                            val slot = (size.width - left) / days.size
                            val i = ((tap.x - left) / slot).toInt()
                            selected = if (i in days.indices && i != selected) i else null
                        }
                    },
            ) {
                val left = AXIS_WIDTH.toPx()
                val plotTop = TOP_ROOM.toPx()
                val plotBottom = size.height - X_LABEL_HEIGHT.toPx()
                val plotH = plotBottom - plotTop
                val gap = 2.dp.toPx()

                listOf(0.0, top / 2, top).forEach { v ->
                    val y = plotBottom - (v / top * plotH).toFloat()
                    gridLine(colors, y, left, size.width)
                    val text = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.GERMANY, "%.1f", v)
                    axisLabelRight(measurer, text, axisStyle, left - 8.dp.toPx(), y)
                }

                val slot = (size.width - left) / days.size
                val barW = minOf(slot * 0.55f, MAX_BAR.toPx())
                days.forEachIndexed { i, day ->
                    val cx = left + slot * i + slot / 2
                    val barLeft = cx - barW / 2
                    val present = reasons.filter { (day.counts[it] ?: 0) > 0 }
                    var bottom = plotBottom
                    present.forEachIndexed { j, reason ->
                        val h = ((day.counts[reason] ?: 0) / top * plotH).toFloat()
                        val isTop = j == present.lastIndex
                        // 2px surface gap between stacked segments.
                        val segTop = bottom - h
                        val drawBottom = if (j == 0) bottom else bottom - gap
                        drawBar(colors.reasonColor(reason), barLeft, segTop, barW, drawBottom, roundTop = isTop)
                        bottom = segTop
                    }
                    if (day.total > 0) {
                        labelCentered(measurer, day.total.toString(), valueStyle, cx, bottom - 18.dp.toPx())
                    }
                    val label = if (day.start == today) "Today" else weekdayShort(day.start)
                    labelCentered(measurer, label, axisStyle, cx, plotBottom + 4.dp.toPx())
                }

                selected?.let { i ->
                    val day = days[i]
                    val detail = reasons.mapNotNull { r -> day.counts[r]?.let { "$it ${reasonLabel(r).lowercase()}" } }
                    val text = if (detail.isEmpty()) "${weekdayShort(day.start)}: none" else "${weekdayShort(day.start)}: ${detail.joinToString(", ")}"
                    val cx = left + slot * i + slot / 2
                    val y = plotBottom - (day.total / top * plotH).toFloat()
                    tooltip(measurer, text, tipStyle, colors, Offset(cx, y - 16.dp.toPx()))
                }
            }
            if (maxTotal == 0) EmptyChartNote("No top-ups in the last 7 days", Modifier.align(Alignment.Center))
        }
    }
}
