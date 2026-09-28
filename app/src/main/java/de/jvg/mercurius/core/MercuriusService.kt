package de.jvg.mercurius.core

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import de.jvg.mercurius.MainActivity
import de.jvg.mercurius.R
import java.util.Calendar

/**
 * Keeps the hotspot online: tops up O2 data with WEITER (proactively or when O2 asks),
 * checks that speed comes back, reconnects when it doesn't, and keeps the hotspot running.
 * All work runs on one background thread, so no state here needs locking.
 */
class MercuriusService : Service() {

    companion object {
        const val ACTION_SMS = "de.jvg.mercurius.SMS"
        const val ACTION_SEND_WEITER = "de.jvg.mercurius.SEND_WEITER"
        const val ACTION_SPEED_TEST = "de.jvg.mercurius.SPEED_TEST"
        const val ACTION_RECONNECT = "de.jvg.mercurius.RECONNECT"
        const val ACTION_AIRPLANE = "de.jvg.mercurius.AIRPLANE"
        const val ACTION_CHECK_HOTSPOT = "de.jvg.mercurius.CHECK_HOTSPOT"
        const val ACTION_DIAGNOSTICS = "de.jvg.mercurius.DIAGNOSTICS"
        const val ACTION_RESET_COUNTER = "de.jvg.mercurius.RESET_COUNTER"
        const val ACTION_SETTINGS_CHANGED = "de.jvg.mercurius.SETTINGS_CHANGED"
        private const val ACTION_STOP = "de.jvg.mercurius.STOP"
        private const val ACTION_SMS_SENT = "de.jvg.mercurius.SMS_SENT"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SENDER = "sender"

        private const val O2_NUMBER = "80112"
        private const val MB = 1_000_000L
        private const val MINUTE = 60_000L
        private const val TICK_MS = 15_000L
        private const val VERIFY_MS = 90_000L
        private const val GIVE_UP_MS = 30 * MINUTE
        private const val WEITER_COOLDOWN_MS = 90_000L
        private const val WEITER_RETRY_MS = 5 * MINUTE
        private const val MAX_WEITER_PER_HOUR = 20
        private const val SMS_NO_POPUP_MS = 10_000L
        private const val SMS_RETRY_MS = 30_000L
        private const val SMS_STUCK_MS = MINUTE
        private const val HOTSPOT_CHECK_MS = MINUTE
        private const val HOTSPOT_BACKOFF_MS = 30 * MINUTE
        private const val USAGE_SYNC_MS = 10 * MINUTE
        private const val BUSY_KBPS = 1000L

        private const val STATUS_ID = 1
        private const val ALERT_ID = 2

        // O2's prompts ("…80% … verbraucht … mit WEITER antworten", "… verbraucht … reduzierter
        // Geschwindigkeit … WEITER …"). Case-sensitive so "weitere"/"weitersurfen" don't match.
        private val WEITER_WORD = Regex("""\bWEITER\b""")
        private const val USED_UP_WORD = "verbraucht"
        private const val CONFIRMATION = "Zusatzvolumen wurde erfolgreich aktiviert"

        @Volatile private var instance: MercuriusService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MercuriusService::class.java))
        }

        fun stop(context: Context) {
            instance?.let { it.handler.post { it.stopSelf() } }
        }

        fun send(context: Context, action: String, text: String? = null, sender: String? = null) {
            val running = instance
            if (running != null) {
                running.handler.post { running.handle(action, text, sender) }
            } else {
                context.startForegroundService(
                    Intent(context, MercuriusService::class.java)
                        .setAction(action)
                        .putExtra(EXTRA_TEXT, text)
                        .putExtra(EXTRA_SENDER, sender),
                )
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var counter: DataCounter
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notifications: NotificationManager

    private val tickRunnable = Runnable { tick() }
    private var nextHealthAt = 0L
    private var nextHotspotCheckAt = 0L
    private var nextUsageSyncAt = 0L
    private var hotspotOn: Boolean? = null
    private var lastNotificationText = ""
    private var lastAlertText = ""
    private var lastAlertAt = 0L
    private var lastSkipLogAt = 0L
    private var retriedFailedSend = false

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val waited = System.currentTimeMillis() - prefs.weiterPendingSince
            prefs.weiterPendingSince = 0
            if (resultCode == Activity.RESULT_OK) {
                AppLog.i("WEITER sent after ${waited / 1000}s")
                // A quick send means no one had to tap Android's short code popup.
                if (waited < SMS_NO_POPUP_MS) prefs.premiumSmsOk = true
                retriedFailedSend = false
            } else if (resultCode == SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED ||
                resultCode == SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED
            ) {
                alert("Android blocked WEITER to the short code. Set Premium SMS access for Mercurius to Always allow.")
            } else if (!retriedFailedSend) {
                // The modem occasionally rejects a single SMS (seen: error 104); one retry usually goes through.
                retriedFailedSend = true
                AppLog.i("WEITER failed (error $resultCode), retrying in 30s")
                handler.postDelayed({ sendWeiter(TopUpReason.MANUAL, "retry after error $resultCode", force = true, retry = true) }, SMS_RETRY_MS)
            } else {
                retriedFailedSend = false
                alert("Sending WEITER failed twice (error $resultCode)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        counter = DataCounter(this, prefs)
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()
        startForeground(STATUS_ID, statusNotification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        thread = HandlerThread("mercurius").apply { start() }
        handler = Handler(thread.looper)
        // The phone lives on a charger, so holding the CPU awake costs nothing and keeps checks on time.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mercurius:service")
            .apply { acquire() }
        registerReceiver(smsSentReceiver, IntentFilter(ACTION_SMS_SENT), RECEIVER_NOT_EXPORTED)
        Shell.onReady = { handler.post { if (prefs.keepHotspot) ensureHotspot() } }

        instance = this
        AppLog.i("Service started")
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            STATUS_ID,
            statusNotification(lastNotificationText.ifEmpty { "Starting" }),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val action = intent?.action
        if (action != null) {
            val text = intent.getStringExtra(EXTRA_TEXT)
            val sender = intent.getStringExtra(EXTRA_SENDER)
            handler.post { handle(action, text, sender) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Shell.onReady = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        unregisterReceiver(smsSentReceiver)
        if (wakeLock.isHeld) wakeLock.release()
        History.flush()
        StatusStore.update { it.copy(serviceRunning = false) }
        AppLog.i("Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handle(action: String, text: String?, sender: String?) {
        when (action) {
            ACTION_SMS -> onSms(text.orEmpty(), sender.orEmpty())
            ACTION_SEND_WEITER -> sendWeiter(TopUpReason.MANUAL, "sent by hand", force = true)
            ACTION_SPEED_TEST -> AppLog.i("Speed test: ${probe()} kbit/s")
            ACTION_RECONNECT -> reconnectData()
            ACTION_AIRPLANE -> toggleAirplane()
            ACTION_CHECK_HOTSPOT -> {
                nextHotspotCheckAt = 0
                ensureHotspot(verbose = true)
            }
            ACTION_DIAGNOSTICS -> diagnostics()
            ACTION_RESET_COUNTER -> {
                counter.reset()
                AppLog.i("Counter reset by hand")
            }
            ACTION_SETTINGS_CHANGED -> {
                nextHotspotCheckAt = 0
                nextHealthAt = minOf(nextHealthAt, System.currentTimeMillis() + prefs.checkIntervalMin * MINUTE)
            }
            ACTION_STOP -> stopSelf()
        }
        publishStatus()
    }

    private fun tick() {
        try {
            val newBytes = counter.update()
            History.addUsage(newBytes)

            if (prefs.proactive && onDemandActive() && counter.sinceTopUp >= prefs.thresholdMb * MB) {
                sendWeiter(TopUpReason.PROACTIVE, "${formatMb(counter.sinceTopUp)} used since last top-up")
            }

            val now = System.currentTimeMillis()
            if (now >= nextUsageSyncAt) {
                nextUsageSyncAt = now + USAGE_SYNC_MS
                counter.hourlyHistory()?.let(History::replaceHourly)
            }
            val pending = prefs.weiterPendingSince
            if (pending > 0 && now - pending > SMS_STUCK_MS) {
                prefs.premiumSmsOk = false
                alert("WEITER is waiting for a tap on Android's short code popup. Set Premium SMS access for Mercurius to Always allow.")
            }
            if (prefs.keepHotspot && now >= nextHotspotCheckAt) {
                nextHotspotCheckAt = now + HOTSPOT_CHECK_MS
                ensureHotspot()
            }
            if (now >= nextHealthAt) healthCheck()
            publishStatus()
        } catch (e: Exception) {
            AppLog.i("Tick failed: $e")
        }
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun onSms(text: String, sender: String) {
        AppLog.i("SMS from $sender: ${text.replace('\n', ' ').take(300)}")
        if (!sender.endsWith(O2_NUMBER)) return
        History.add(SmsEvent(System.currentTimeMillis(), sender, text))

        val usedUp = text.contains(USED_UP_WORD, ignoreCase = true) && WEITER_WORD.containsMatchIn(text)
        // A confirmation proves on-demand mode too, e.g. after a WEITER sent by hand.
        if (usedUp || text.contains(CONFIRMATION)) startOnDemand()
        if (usedUp) sendWeiter(TopUpReason.O2_SMS, "O2 says the volume is used up")
    }

    /** O2's base volume is daily ("bis zum Ende des Tages"), so on-demand mode ends at midnight. */
    private fun onDemandActive() = prefs.onDemandSince >= startOfToday()

    private fun startOnDemand() {
        if (onDemandActive()) return
        prefs.onDemandSince = System.currentTimeMillis()
        counter.reset()
        AppLog.i("Today's O2 volume is used up: on-demand top-ups on until midnight")
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** [retry] resends a WEITER that failed, keeping the original reason and not counting it twice. */
    private fun sendWeiter(reason: TopUpReason, why: String, force: Boolean = false, retry: Boolean = false) {
        val now = System.currentTimeMillis()
        val sinceLast = now - prefs.lastWeiterAt
        val lastHour = prefs.weiterTimes.filter { now - it < 60 * MINUTE }

        if (!force && sinceLast < WEITER_COOLDOWN_MS) {
            logSkip("Skipped WEITER ($why): last one ${sinceLast / 1000}s ago")
            return
        }
        if (!force && lastHour.size >= MAX_WEITER_PER_HOUR) {
            alert("Reached the safety limit of $MAX_WEITER_PER_HOUR WEITER per hour. Pausing top-ups.")
            return
        }

        try {
            val sent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_SMS_SENT).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(SmsManager::class.java).sendTextMessage(O2_NUMBER, null, "WEITER", sent, null)
        } catch (e: Exception) {
            alert("Could not send WEITER: ${e.message}")
            return
        }

        prefs.lastWeiterAt = now
        prefs.weiterPendingSince = now
        if (!retry) {
            History.add(TopUpEvent(now, reason, counter.sinceTopUp))
            prefs.weiterTimes = lastHour + now
            counter.reset()
        }
        nextHealthAt = now + VERIFY_MS
        AppLog.i("Sending WEITER: $why")
    }

    /**
     * Checks that the connection is fast. When it's slow: send WEITER first, and if speed
     * doesn't come back, reconnect mobile data, then toggle airplane mode, then ask for help.
     */
    private fun healthCheck() {
        val now = System.currentTimeMillis()
        nextHealthAt = now + prefs.checkIntervalMin * MINUTE
        val kbps = currentSpeed()

        if (kbps >= THROTTLED_KBPS) {
            if (prefs.recoveryLevel > 0) AppLog.i("Speed is back: $kbps kbit/s")
            prefs.recoveryLevel = 0
            StatusStore.update { it.copy(alert = null) }
            return
        }

        AppLog.i("Connection slow: $kbps kbit/s")
        if (now - prefs.lastWeiterAt > WEITER_RETRY_MS) {
            sendWeiter(TopUpReason.SLOW, "connection slow ($kbps kbit/s)")
            return
        }

        when (prefs.recoveryLevel) {
            0 -> {
                AppLog.i("Still slow after WEITER, reconnecting mobile data")
                if (!reconnectData()) alert("Still slow after WEITER. Start Shizuku so Mercurius can reconnect.")
            }
            1 -> {
                AppLog.i("Still slow, toggling airplane mode")
                if (!toggleAirplane()) alert("Still slow after WEITER. Start Shizuku so Mercurius can reconnect.")
            }
            else -> {
                History.add(RecoveryEvent(now, RecoveryKind.GAVE_UP, false))
                alert("Still slow after WEITER, reconnecting and airplane mode. Trying again in 30 minutes.")
                prefs.recoveryLevel = 0
                nextHealthAt = now + GIVE_UP_MS
                return
            }
        }
        prefs.recoveryLevel += 1
        nextHealthAt = System.currentTimeMillis() + VERIFY_MS
    }

    /** Live traffic proves the connection is fast without spending data; otherwise run a probe. */
    private fun currentSpeed(): Long {
        val passive = counter.rateKbps
        if (passive >= BUSY_KBPS) {
            History.add(SpeedEvent(System.currentTimeMillis(), passive, probed = false))
            return passive
        }
        return maxOf(passive, probe())
    }

    private fun probe(): Long {
        val kbps = SpeedProbe.run()
        val now = System.currentTimeMillis()
        prefs.lastProbeAt = now
        prefs.lastProbeKbps = kbps
        History.add(SpeedEvent(now, kbps, probed = true))
        return kbps
    }

    private fun reconnectData(): Boolean {
        val out = Hotspot.reconnectData()
        if (out == null) {
            AppLog.i("Can't reconnect mobile data: Shizuku ${Shell.state.name.lowercase()}")
            return false
        }
        AppLog.i("Reconnected mobile data")
        History.add(RecoveryEvent(System.currentTimeMillis(), RecoveryKind.RECONNECT, out.startsWith("exit=0")))
        if (prefs.keepHotspot) {
            Thread.sleep(5_000)
            ensureHotspot()
        }
        return true
    }

    private fun toggleAirplane(): Boolean {
        val out = Hotspot.toggleAirplane()
        if (out == null) {
            AppLog.i("Can't toggle airplane mode: Shizuku ${Shell.state.name.lowercase()}")
            return false
        }
        AppLog.i("Toggled airplane mode")
        History.add(RecoveryEvent(System.currentTimeMillis(), RecoveryKind.AIRPLANE, out.startsWith("exit=0")))
        // Airplane mode switches the hotspot off, so bring it back once the radio is up again.
        Thread.sleep(10_000)
        ensureHotspot()
        return true
    }

    private fun ensureHotspot(verbose: Boolean = false) {
        val on = Hotspot.isOn()
        hotspotOn = on
        when {
            on == null -> if (verbose) AppLog.i("Can't read hotspot state: Shizuku ${Shell.state.name.lowercase()}")
            on -> if (verbose) AppLog.i("Hotspot is on")
            else -> restartHotspot()
        }
    }

    private fun restartHotspot() {
        AppLog.i("Hotspot is off, starting it")
        AppLog.i("Start hotspot: ${Shell.startHotspot()}")
        Thread.sleep(5_000)
        val ok = Hotspot.isOn() == true
        hotspotOn = ok
        History.add(RecoveryEvent(System.currentTimeMillis(), RecoveryKind.HOTSPOT_RESTART, ok))
        if (ok) {
            AppLog.i("Hotspot is back on")
        } else {
            // Don't keep poking the hotspot every minute if restarting doesn't work on this phone.
            nextHotspotCheckAt = System.currentTimeMillis() + HOTSPOT_BACKOFF_MS
            alert("Could not restart the hotspot. See the log in Settings.")
        }
    }

    private fun diagnostics() {
        AppLog.i("Diagnostics: Shizuku ${Shell.state}, usage access ${counter.hasUsageAccess}")
        AppLog.i("Since top-up: NetworkStats ${formatMb(prefs.nsSince)}, TrafficStats ${formatMb(prefs.tsSince)}")
        Shell.exec("dumpsys tethering | grep -A8 'Tether state'")?.let { AppLog.i("Tether state:\n$it") }
        AppLog.i("Hotspot detected as: ${Hotspot.isOn()}")
    }

    private fun publishStatus() {
        val now = System.currentTimeMillis()
        StatusStore.update {
            it.copy(
                serviceRunning = true,
                sinceTopUpBytes = counter.sinceTopUp,
                networkStatsBytes = prefs.nsSince,
                trafficStatsBytes = prefs.tsSince,
                thresholdBytes = prefs.thresholdMb * MB,
                proactive = prefs.proactive,
                onDemandActive = onDemandActive(),
                onDemandSince = prefs.onDemandSince,
                rateKbps = counter.rateKbps,
                lastTopUpAt = prefs.lastWeiterAt,
                topUpsLastHour = prefs.weiterTimes.count { t -> now - t < 60 * MINUTE },
                lastSpeedKbps = prefs.lastProbeKbps,
                lastSpeedAt = prefs.lastProbeAt,
                shizuku = Shell.state,
                hotspotOn = hotspotOn,
                usageAccess = counter.hasUsageAccess,
                recoveryLevel = prefs.recoveryLevel,
            )
        }
        val hotspot = when (hotspotOn) {
            true -> "hotspot on"
            false -> "hotspot off"
            null -> "hotspot unknown"
        }
        val text = "${formatMb(counter.sinceTopUp)} since last WEITER · $hotspot"
        if (text != lastNotificationText) {
            lastNotificationText = text
            notifications.notify(STATUS_ID, statusNotification(text))
        }
    }

    private fun logSkip(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastSkipLogAt < 5 * MINUTE) return
        lastSkipLogAt = now
        AppLog.i(message)
    }

    private fun alert(text: String) {
        StatusStore.update { it.copy(alert = text) }
        val now = System.currentTimeMillis()
        if (text == lastAlertText && now - lastAlertAt < 10 * MINUTE) return
        lastAlertText = text
        lastAlertAt = now
        AppLog.i("ALERT: $text")
        notifications.notify(
            ALERT_ID,
            Notification.Builder(this, "alerts")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Mercurius needs attention")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel("status", "Status", NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun statusNotification(text: String) = Notification.Builder(this, "status")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Mercurius")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}
