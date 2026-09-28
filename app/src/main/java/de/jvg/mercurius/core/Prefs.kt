package de.jvg.mercurius.core

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User settings plus the small amount of service state that must survive restarts. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mercurius", Context.MODE_PRIVATE)

    // Settings

    /** Send WEITER once this much data went through since the last top-up. */
    var thresholdMb by int("thresholdMb", 1800)

    /** Top up based on measured usage instead of waiting for O2's SMS. */
    var proactive by bool("proactive", true)

    /** Restart the hotspot whenever it is off (needs Shizuku). */
    var keepHotspot by bool("keepHotspot", true)

    /** Minutes between speed checks while nothing else is going on. */
    var checkIntervalMin by int("checkIntervalMin", 10)

    // Service state

    var nsSince by long("nsSince", 0)
    var tsSince by long("tsSince", 0)
    var nsLast by long("nsLast", -1)
    var tsLast by long("tsLast", -1)
    var lastWeiterAt by long("lastWeiterAt", 0)

    /** When O2 first said "verbraucht" today (epoch millis). On-demand mode lasts until midnight. */
    var onDemandSince by long("onDemandSince", 0)

    /** When the last WEITER was handed to Android and hasn't been confirmed as sent yet, else 0. */
    var weiterPendingSince by long("weiterPendingSince", 0)

    /** True once a WEITER went out without Android's premium SMS confirmation popup. */
    var premiumSmsOk by bool("premiumSmsOk", false)
    var recoveryLevel by int("recoveryLevel", 0)
    var lastProbeAt by long("lastProbeAt", 0)
    var lastProbeKbps by long("lastProbeKbps", -1)

    var weiterTimes: List<Long>
        get() = sp.getString("weiterTimes", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        set(value) = sp.edit().putString("weiterTimes", value.joinToString(",")).apply()

    private fun int(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            sp.edit().putInt(key, value).apply()
    }

    private fun long(key: String, default: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) =
            sp.edit().putLong(key, value).apply()
    }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    private fun string(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }
}
