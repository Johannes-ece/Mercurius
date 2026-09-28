package de.jvg.mercurius.core

import android.content.Context

/** Everything the UI can ask the service to do. Each call starts the service if needed. */
object Commands {
    fun start(context: Context) = MercuriusService.start(context)
    fun stop(context: Context) = MercuriusService.stop(context)

    fun sendWeiterNow(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_SEND_WEITER)
    fun speedTest(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_SPEED_TEST)
    fun reconnectData(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_RECONNECT)
    fun toggleAirplane(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_AIRPLANE)
    fun checkHotspot(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_CHECK_HOTSPOT)

    /** Writes tethering state, counters and command help into the log for debugging. */
    fun diagnostics(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_DIAGNOSTICS)

    /** Resets the "used since last top-up" counter without sending anything. */
    fun resetCounter(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_RESET_COUNTER)

    /** Re-reads settings into the live status (call after saving settings). */
    fun settingsChanged(context: Context) = MercuriusService.send(context, MercuriusService.ACTION_SETTINGS_CHANGED)
}
