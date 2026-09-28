package de.jvg.mercurius.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.outlined.WifiTetheringOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jvg.mercurius.core.THROTTLED_KBPS
import de.jvg.mercurius.core.Event
import de.jvg.mercurius.core.History
import de.jvg.mercurius.core.RecoveryEvent
import de.jvg.mercurius.core.RecoveryKind
import de.jvg.mercurius.core.ShizukuState
import de.jvg.mercurius.core.SmsEvent
import de.jvg.mercurius.core.SpeedEvent
import de.jvg.mercurius.core.Status
import de.jvg.mercurius.core.StatusStore
import de.jvg.mercurius.core.TopUpEvent
import de.jvg.mercurius.core.TopUpReason
import kotlinx.coroutines.delay

/** Below this the service treats the connection as throttled (mirrors the service's threshold). */

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val status by StatusStore.state.collectAsStateWithLifecycle()
    val history by History.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    // Aggregate on new history or once a minute, never per frame.
    val stats = remember(history, now / 60_000) { computeStats(history, now) }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val colors = rememberChartColors(cardColor)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Mercurius", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        status.alert?.let { AlertBanner(it) }
        HeroCard(status, colors, cardColor)
        Tiles(stats, status, now, cardColor)
        ChartCard("Mobile data per hour", "Last 24 hours, hotspot traffic included. Tap a bar for details.", cardColor) {
            HourlyUsageChart(stats.hourly, colors)
        }
        ChartCard("Speed", "Last 24 hours, log scale", cardColor) {
            SpeedChart(stats.speedPoints, now, THROTTLED_KBPS, colors)
        }
        ChartCard("WEITER top-ups", "Per day, last 7 days", cardColor) {
            TopUpChart(stats.days, now, colors)
        }
        TimelineCard(stats.timeline, now, colors, cardColor)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AlertBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun HeroCard(status: Status, colors: ChartColors, cardColor: Color) {
    val used = status.sinceTopUpBytes
    val threshold = status.thresholdBytes
    val caption = when {
        !status.proactive -> "Proactive top-up is off. Waiting for O2's SMS."
        !status.onDemandActive -> "Daily volume active. Waiting for O2's first \"verbraucht\" SMS."
        used >= threshold -> "Threshold reached, sending WEITER"
        else -> "Next WEITER in ${formatBytes(threshold - used)} (at ${formatBytes(threshold)})"
    }

    Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UsageGauge(used, threshold, colors, Modifier.size(216.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status.serviceRunning) StatusPill(Icons.Outlined.CheckCircle, "Service running", colors.good)
                else StatusPill(Icons.Outlined.Cancel, "Service stopped", colors.critical)

                when (status.hotspotOn) {
                    true -> StatusPill(Icons.Outlined.WifiTethering, "Hotspot on", colors.good)
                    false -> StatusPill(Icons.Outlined.WifiTetheringOff, "Hotspot off", colors.critical)
                    null -> StatusPill(Icons.Outlined.WifiTethering, "Hotspot unknown", MaterialTheme.colorScheme.onSurfaceVariant)
                }

                when (status.shizuku) {
                    ShizukuState.READY -> StatusPill(Icons.Outlined.Terminal, "Shizuku ready", colors.good)
                    ShizukuState.CONNECTING -> StatusPill(Icons.Outlined.Terminal, "Shizuku connecting", MaterialTheme.colorScheme.onSurfaceVariant)
                    ShizukuState.NO_PERMISSION -> StatusPill(Icons.Outlined.Terminal, "Shizuku needs permission", colors.warning)
                    ShizukuState.NOT_RUNNING -> StatusPill(Icons.Outlined.Terminal, "Shizuku not running", colors.warning)
                }

                StatusPill(Icons.Outlined.Speed, "${formatSpeed(status.rateKbps)} now", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 270 degree arc: data used since the last WEITER against the proactive threshold. */
@Composable
private fun UsageGauge(used: Long, threshold: Long, colors: ChartColors, modifier: Modifier = Modifier) {
    val fraction = if (threshold > 0) (used.toFloat() / threshold).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(fraction, tween(700), label = "gauge")
    val (number, unit) = splitBytes(used)
    val accent = colors.series[0]

    Box(
        modifier.clearAndSetSemantics {
            contentDescription = "${formatBytes(used)} used since the last WEITER, of ${formatBytes(threshold)}"
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(accent.copy(alpha = 0.16f), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(accent, 135f, 270f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(number, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
            Text("since last WEITER", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** State pill: the icon carries the status color, the label stays in text ink. */
@Composable
private fun StatusPill(icon: ImageVector, label: String, tint: Color) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun Tiles(stats: StatsModel, status: Status, now: Long, cardColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Outlined.DataUsage, "Data today", formatBytes(stats.dataToday), "Since midnight", cardColor, Modifier.weight(1f))
            StatTile(
                Icons.Outlined.Autorenew, "Top-ups today", stats.topUpsToday.toString(),
                "${status.topUpsLastHour} in the last hour", cardColor, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                Icons.Outlined.Speed, "Median speed", stats.medianSpeed24h?.let(::formatSpeed) ?: "No tests",
                "Speed tests, last 24 h", cardColor, Modifier.weight(1f),
            )
            StatTile(
                Icons.Outlined.Schedule, "Last top-up", relativeTime(status.lastTopUpAt, now),
                if (status.lastTopUpAt > 0) "at ${clockTime(status.lastTopUpAt)}" else "None yet", cardColor, Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, caption: String, cardColor: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, cardColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
private fun TimelineCard(days: List<TimelineDay>, now: Long, colors: ChartColors, cardColor: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (days.isEmpty()) {
                Text(
                    "Nothing has happened yet. Top-ups, recoveries and O2 messages will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val today = startOfDay(now)
            days.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    dayHeading(day.start, now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                day.events.forEach { event ->
                    TimelineRow(event, if (day.start == today) relativeTime(event.at, now) else clockTime(event.at), colors)
                }
            }
        }
    }
}

private data class EventLook(val icon: ImageVector, val tint: Color, val title: String, val detail: String?)

@Composable
private fun lookOf(event: Event, colors: ChartColors): EventLook {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when (event) {
        is TopUpEvent -> {
            val why = when (event.reason) {
                TopUpReason.PROACTIVE -> "Proactive"
                TopUpReason.O2_SMS -> "O2 asked via SMS"
                TopUpReason.SLOW -> "Connection was slow"
                TopUpReason.MANUAL -> "Sent by hand"
            }
            EventLook(Icons.Outlined.Autorenew, colors.reasonColor(event.reason), "WEITER sent", "$why, ${formatBytes(event.usedBytes)} since the previous one")
        }
        is RecoveryEvent -> {
            val (icon, title) = when (event.kind) {
                RecoveryKind.RECONNECT -> Icons.Outlined.SignalCellularAlt to "Reconnected mobile data"
                RecoveryKind.AIRPLANE -> Icons.Outlined.AirplanemodeActive to "Toggled airplane mode"
                RecoveryKind.HOTSPOT_RESTART -> Icons.Outlined.WifiTethering to "Restarted hotspot"
                RecoveryKind.GAVE_UP -> Icons.Outlined.ErrorOutline to "Recovery paused for 30 min"
            }
            val ok = event.ok && event.kind != RecoveryKind.GAVE_UP
            EventLook(icon, if (ok) colors.good else colors.critical, title, if (ok) "Succeeded" else "Did not help")
        }
        is SmsEvent -> EventLook(Icons.Outlined.Sms, muted, "SMS from ${event.sender.ifBlank { "unknown" }}", event.text.replace('\n', ' '))
        is SpeedEvent -> EventLook(Icons.Outlined.Speed, muted, "Speed ${formatSpeed(event.kbps)}", null)
    }
}

@Composable
private fun TimelineRow(event: Event, time: String, colors: ChartColors) {
    val look = lookOf(event, colors)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(look.title, style = MaterialTheme.typography.bodyLarge)
            look.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
