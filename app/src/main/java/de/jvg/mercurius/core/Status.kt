package de.jvg.mercurius.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Below this speed the connection counts as throttled by O2. */
const val THROTTLED_KBPS = 400L

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

/** Live snapshot of what the service knows, refreshed every tick. */
data class Status(
    val serviceRunning: Boolean = false,
    /** Best estimate of mobile data used since the last WEITER. */
    val sinceTopUpBytes: Long = 0,
    /** Same, from NetworkStatsManager (includes hotspot traffic, may lag). */
    val networkStatsBytes: Long = 0,
    /** Same, from TrafficStats (instant, may miss offloaded hotspot traffic). */
    val trafficStatsBytes: Long = 0,
    val thresholdBytes: Long = 1_800_000_000,
    val proactive: Boolean = true,
    /** O2 said today's volume is used up ("verbraucht"), so top-ups are on until midnight. */
    val onDemandActive: Boolean = false,
    val onDemandSince: Long = 0,
    /** Mobile throughput over the last tick. */
    val rateKbps: Long = 0,
    val lastTopUpAt: Long = 0,
    val topUpsLastHour: Int = 0,
    val lastSpeedKbps: Long = -1,
    val lastSpeedAt: Long = 0,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    /** null when unknown (Shizuku not ready or unexpected system output). */
    val hotspotOn: Boolean? = null,
    val usageAccess: Boolean = false,
    /** 0 = healthy, 1 = reconnected data, 2 = toggled airplane mode. */
    val recoveryLevel: Int = 0,
    /** Latest problem that needs a human, cleared when things recover. */
    val alert: String? = null,
)

object StatusStore {
    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    fun update(transform: (Status) -> Status) = _state.update(transform)
}
