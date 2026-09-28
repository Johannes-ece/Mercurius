package de.jvg.mercurius.ui.stats

import de.jvg.mercurius.core.Event
import de.jvg.mercurius.core.HistorySnapshot
import de.jvg.mercurius.core.SpeedEvent
import de.jvg.mercurius.core.TopUpEvent
import de.jvg.mercurius.core.TopUpReason
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

internal const val HOUR_MS = 3_600_000L
private val GERMAN = Locale.GERMANY

internal data class HourBar(val start: Long, val bytes: Long, val current: Boolean)

internal data class SpeedPoint(val at: Long, val kbps: Long, val probed: Boolean)

internal data class DayTopUps(val start: Long, val counts: Map<TopUpReason, Int>) {
    val total: Int get() = counts.values.sum()
}

internal data class TimelineDay(val start: Long, val events: List<Event>)

/** Everything the stats screen shows, aggregated once per minute instead of every frame. */
internal data class StatsModel(
    val hourly: List<HourBar>,
    val dataToday: Long,
    val topUpsToday: Int,
    val medianSpeed24h: Long?,
    val speedPoints: List<SpeedPoint>,
    val days: List<DayTopUps>,
    val timeline: List<TimelineDay>,
)

internal fun computeStats(history: HistorySnapshot, now: Long): StatsModel {
    // History buckets hours in UTC; German time zones are whole-hour offsets, so they line up with local hours.
    val currentHour = now - now % HOUR_MS
    val hourly = (23 downTo 0).map { i ->
        val start = currentHour - i * HOUR_MS
        HourBar(start, history.hourlyBytes[start] ?: 0, current = i == 0)
    }

    val today = startOfDay(now)
    val dataToday = history.hourlyBytes.filterKeys { it >= today }.values.sum()

    val topUps = history.events.filterIsInstance<TopUpEvent>()
    val topUpsToday = topUps.count { it.at >= today }

    val dayAgo = now - 24 * HOUR_MS
    val speedPoints = history.events.filterIsInstance<SpeedEvent>()
        .filter { it.at >= dayAgo }
        .map { SpeedPoint(it.at, it.kbps, it.probed) }
    val probed = speedPoints.filter { it.probed }.map { it.kbps }.sorted()
    val median = when {
        probed.isEmpty() -> null
        probed.size % 2 == 1 -> probed[probed.size / 2]
        else -> (probed[probed.size / 2 - 1] + probed[probed.size / 2]) / 2
    }

    val days = (6 downTo 0).map { i ->
        val start = startOfDay(now, daysAgo = i)
        val end = startOfDay(now, daysAgo = i - 1)
        DayTopUps(start, topUps.filter { it.at in start until end }.groupingBy { it.reason }.eachCount())
    }

    val timeline = history.events
        .filter { it !is SpeedEvent }
        .takeLast(30)
        .reversed()
        .groupBy { startOfDay(it.at) }
        .map { (start, events) -> TimelineDay(start, events) }

    return StatsModel(hourly, dataToday, topUpsToday, median, speedPoints, days, timeline)
}

internal fun startOfDay(time: Long, daysAgo: Int = 0): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_MONTH, -daysAgo)
    timeInMillis
}

internal fun localHour(time: Long): Int = Calendar.getInstance().run {
    timeInMillis = time
    get(Calendar.HOUR_OF_DAY)
}

// Formatting: English labels, German number style (1,24 GB and 1.024 MB).

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(GERMAN, "%.2f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(GERMAN, "%,d MB", bytes / 1_000_000)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** Split into number and unit for the hero figure. */
internal fun splitBytes(bytes: Long): Pair<String, String> = when {
    bytes >= 1_000_000_000 -> String.format(GERMAN, "%.2f", bytes / 1e9) to "GB"
    else -> String.format(GERMAN, "%,d", bytes / 1_000_000) to "MB"
}

internal fun formatAxisBytes(bytes: Double): String = when {
    bytes <= 0.0 -> "0"
    bytes >= 1e9 -> String.format(GERMAN, "%.1f GB", bytes / 1e9).replace(",0 ", " ")
    else -> String.format(GERMAN, "%,.0f MB", bytes / 1e6)
}

internal fun formatSpeed(kbps: Long): String = when {
    kbps >= 10_000 -> String.format(GERMAN, "%,.0f Mbit/s", kbps / 1000.0)
    kbps >= 1_000 -> String.format(GERMAN, "%.1f Mbit/s", kbps / 1000.0)
    else -> "$kbps kbit/s"
}

internal fun relativeTime(at: Long, now: Long): String {
    if (at <= 0) return "Never"
    val seconds = (now - at).coerceAtLeast(0) / 1000
    return when {
        seconds < 45 -> "Just now"
        seconds < 3_600 -> "${(seconds + 30) / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

internal fun clockTime(at: Long): String = SimpleDateFormat("HH:mm", GERMAN).format(Date(at))

internal fun weekdayShort(at: Long): String = SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(at))

internal fun dayHeading(dayStart: Long, now: Long): String = when (dayStart) {
    startOfDay(now) -> "Today"
    startOfDay(now, daysAgo = 1) -> "Yesterday"
    else -> SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(dayStart))
}

/** Rounds up to 1, 2 or 5 times a power of ten, for clean axis maxima. */
internal fun niceCeil(value: Double): Double {
    if (value <= 0) return 1.0
    val base = 10.0.pow(floor(log10(value)))
    val fraction = value / base
    val nice = when {
        fraction <= 1 -> 1.0
        fraction <= 2 -> 2.0
        fraction <= 5 -> 5.0
        else -> 10.0
    }
    return nice * base
}
