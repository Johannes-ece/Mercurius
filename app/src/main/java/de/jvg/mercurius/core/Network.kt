package de.jvg.mercurius.core

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val MB = 1_000_000L
private const val HOUR_MS = 3_600_000L

fun formatMb(bytes: Long) = "${bytes / MB} MB"

/**
 * Counts mobile data since the last top-up from two sources and trusts the larger one:
 * NetworkStats includes hotspot traffic but can lag, TrafficStats is instant but may miss
 * hotspot traffic that the modem offloads past the kernel.
 */
class DataCounter(context: Context, private val prefs: Prefs) {
    private val stats = context.getSystemService(NetworkStatsManager::class.java)
    private var lastUpdateAt = 0L

    val sinceTopUp: Long get() = maxOf(prefs.nsSince, prefs.tsSince)
    var rateKbps = 0L
        private set
    var hasUsageAccess = false
        private set

    /** Returns the number of new bytes seen since the previous call. */
    fun update(): Long {
        val before = sinceTopUp

        val ns = networkStatsTotal()
        hasUsageAccess = ns != null
        if (ns != null) {
            prefs.nsSince += delta(prefs.nsLast, ns)
            prefs.nsLast = ns
        }

        val ts = kernelTotal()
        if (ts != null) {
            val d = delta(prefs.tsLast, ts)
            prefs.tsSince += d
            prefs.tsLast = ts
            val now = SystemClock.elapsedRealtime()
            if (lastUpdateAt > 0) rateKbps = d * 8 / maxOf(1, now - lastUpdateAt)
            lastUpdateAt = now
        }

        return (sinceTopUp - before).coerceAtLeast(0)
    }

    fun reset() {
        prefs.nsSince = 0
        prefs.tsSince = 0
    }

    /**
     * Mobile bytes per hour for the last [hours] hours, from Android's own records (the same data
     * as Settings > Data usage, hotspot included, also from before Mercurius was installed).
     * Android stores these in 1-hour buckets, so hour-aligned queries are exact.
     */
    fun hourlyHistory(hours: Int = 24): Map<Long, Long>? {
        val now = System.currentTimeMillis()
        val currentHour = now - now % HOUR_MS
        return try {
            (0 until hours).associate { i ->
                val start = currentHour - i * HOUR_MS
                start to deviceBytes(start, start + HOUR_MS)
            }
        } catch (e: Exception) {
            null
        }
    }

    // A counter going backwards means it was reset (reboot, history rotation): count nothing for this step.
    private fun delta(last: Long, current: Long) = if (last < 0 || current < last) 0 else current - last

    private fun networkStatsTotal(): Long? = try {
        deviceBytes(0, System.currentTimeMillis() + 24 * HOUR_MS)
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun deviceBytes(start: Long, end: Long): Long {
        val bucket = stats.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end)
        return bucket.rxBytes + bucket.txBytes
    }

    private fun kernelTotal(): Long? {
        val rx = TrafficStats.getMobileRxBytes()
        val tx = TrafficStats.getMobileTxBytes()
        return if (rx < 0 || tx < 0) null else rx + tx
    }
}

object SpeedProbe {
    private const val URL_500KB = "https://speed.cloudflare.com/__down?bytes=500000"
    private const val MAX_MS = 12_000L

    /** Downloads up to 500 KB and returns kbit/s, 0 if nothing came through. */
    fun run(): Long {
        val start = SystemClock.elapsedRealtime()
        var bytes = 0L
        try {
            val conn = URL(URL_500KB).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.useCaches = false
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (SystemClock.elapsedRealtime() - start < MAX_MS) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                }
            }
            conn.disconnect()
        } catch (e: IOException) {
            AppLog.i("Speed test failed after ${bytes / 1000} KB: ${e.message}")
        }
        return bytes * 8 / maxOf(1, SystemClock.elapsedRealtime() - start)
    }
}

/** Hotspot and mobile data control via shell commands. All return null without Shizuku. */
object Hotspot {
    private val TETHER_LINE = Regex("""^\s*(\S+) - (\w+)""")

    /**
     * Reads the system tethering state. Returns null when it can't tell for sure, so callers
     * never restart a hotspot that is actually running.
     */
    fun isOn(): Boolean? {
        val out = Shell.exec("dumpsys tethering") ?: return null
        val lines = out.lines()
        val header = lines.indexOfFirst { it.trim() == "Tether state:" }
        if (header < 0) return null
        return lines.drop(header + 1)
            .takeWhile { TETHER_LINE.containsMatchIn(it) }
            .mapNotNull { TETHER_LINE.find(it) }
            .any { m ->
                val iface = m.groupValues[1]
                val wifi = iface.startsWith("wlan") || iface.startsWith("ap") || iface.startsWith("swlan")
                wifi && m.groupValues[2] == "TetheredState"
            }
    }

    fun reconnectData(): String? = Shell.exec("svc data disable; sleep 3; svc data enable")

    fun toggleAirplane(): String? =
        Shell.exec("cmd connectivity airplane-mode enable; sleep 5; cmd connectivity airplane-mode disable")
}
