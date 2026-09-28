package de.jvg.mercurius.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TopUpReason { PROACTIVE, O2_SMS, SLOW, MANUAL }
enum class RecoveryKind { RECONNECT, AIRPLANE, HOTSPOT_RESTART, GAVE_UP }

sealed interface Event {
    val at: Long
}

/** A WEITER SMS was sent. [usedBytes] is how much data went through since the previous one. */
data class TopUpEvent(override val at: Long, val reason: TopUpReason, val usedBytes: Long) : Event

/** A speed measurement. [probed] = active download test, otherwise measured from live traffic. */
data class SpeedEvent(override val at: Long, val kbps: Long, val probed: Boolean) : Event

data class RecoveryEvent(override val at: Long, val kind: RecoveryKind, val ok: Boolean) : Event

data class SmsEvent(override val at: Long, val sender: String, val text: String) : Event

data class HistorySnapshot(
    /** Oldest first. */
    val events: List<Event> = emptyList(),
    /** Mobile bytes per hour, keyed by the hour's start in epoch millis. */
    val hourlyBytes: Map<Long, Long> = emptyMap(),
)

/** Analytics history for the stats screen, kept for [RETENTION_MS] and persisted as JSON. */
object History {
    private const val RETENTION_MS = 30L * 24 * 3600 * 1000
    private const val HOUR_MS = 3600L * 1000
    private const val USAGE_SAVE_INTERVAL_MS = 60L * 1000

    private val _state = MutableStateFlow(HistorySnapshot())
    val state: StateFlow<HistorySnapshot> = _state.asStateFlow()

    private var file: File? = null
    private var lastSaveAt = 0L

    fun init(context: Context) = synchronized(this) {
        if (file != null) return
        val f = File(context.filesDir, "history.json")
        file = f
        if (f.exists()) runCatching { _state.value = decode(JSONObject(f.readText())) }
            .onFailure { AppLog.i("Could not read history: $it") }
    }

    fun add(event: Event) = synchronized(this) {
        val s = _state.value
        _state.value = prune(s.copy(events = s.events + event))
        save()
    }

    fun addUsage(bytes: Long, at: Long = System.currentTimeMillis()) = synchronized(this) {
        if (bytes <= 0) return
        val hour = at - at % HOUR_MS
        val s = _state.value
        _state.value = s.copy(hourlyBytes = s.hourlyBytes + (hour to (s.hourlyBytes[hour] ?: 0) + bytes))
        if (at - lastSaveAt > USAGE_SAVE_INTERVAL_MS) save()
    }

    /** Overwrites hourly usage with authoritative values (Android's own records) for the given hours. */
    fun replaceHourly(values: Map<Long, Long>) = synchronized(this) {
        val s = _state.value
        _state.value = s.copy(hourlyBytes = s.hourlyBytes + values.filterValues { it > 0 })
        save()
    }

    fun flush() = synchronized(this) { save() }

    fun clear() = synchronized(this) {
        _state.value = HistorySnapshot()
        save()
    }

    private fun prune(s: HistorySnapshot): HistorySnapshot {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return HistorySnapshot(
            events = s.events.filter { it.at >= cutoff },
            hourlyBytes = s.hourlyBytes.filterKeys { it >= cutoff },
        )
    }

    private fun save() {
        val f = file ?: return
        lastSaveAt = System.currentTimeMillis()
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encode(_state.value).toString())
            tmp.renameTo(f)
        }.onFailure { AppLog.i("Could not save history: $it") }
    }

    private fun encode(s: HistorySnapshot) = JSONObject().apply {
        put("events", JSONArray().apply {
            s.events.forEach { e ->
                put(JSONObject().apply {
                    put("at", e.at)
                    when (e) {
                        is TopUpEvent -> put("type", "topup").put("reason", e.reason.name).put("used", e.usedBytes)
                        is SpeedEvent -> put("type", "speed").put("kbps", e.kbps).put("probed", e.probed)
                        is RecoveryEvent -> put("type", "recovery").put("kind", e.kind.name).put("ok", e.ok)
                        is SmsEvent -> put("type", "sms").put("sender", e.sender).put("text", e.text)
                    }
                })
            }
        })
        put("hourly", JSONObject().apply { s.hourlyBytes.forEach { (hour, bytes) -> put(hour.toString(), bytes) } })
    }

    private fun decode(json: JSONObject): HistorySnapshot {
        val events = mutableListOf<Event>()
        val array = json.optJSONArray("events") ?: JSONArray()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val at = o.getLong("at")
            runCatching {
                when (o.getString("type")) {
                    "topup" -> TopUpEvent(at, TopUpReason.valueOf(o.getString("reason")), o.getLong("used"))
                    "speed" -> SpeedEvent(at, o.getLong("kbps"), o.getBoolean("probed"))
                    "recovery" -> RecoveryEvent(at, RecoveryKind.valueOf(o.getString("kind")), o.getBoolean("ok"))
                    "sms" -> SmsEvent(at, o.getString("sender"), o.getString("text"))
                    else -> null
                }
            }.getOrNull()?.let(events::add)
        }
        val hourly = mutableMapOf<Long, Long>()
        json.optJSONObject("hourly")?.let { h -> h.keys().forEach { k -> hourly[k.toLong()] = h.getLong(k) } }
        return prune(HistorySnapshot(events, hourly))
    }
}
