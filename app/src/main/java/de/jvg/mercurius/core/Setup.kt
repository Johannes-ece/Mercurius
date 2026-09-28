package de.jvg.mercurius.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** One-time setup steps and whether each one is done. */
object Setup {
    val runtimePermissions = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun hasRuntimePermissions(context: Context) = runtimePermissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun ignoresBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun shizukuState(): ShizukuState = Shell.state

    /** Known only after a WEITER went out: true when it needed no tap on Android's short code popup. */
    fun premiumSmsAllowed(context: Context) = Prefs(context).premiumSmsOk

    /** Settings > Apps > Special app access > Premium SMS access. */
    fun premiumSmsIntent(): Intent =
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$PremiumSmsAccessActivity")

    fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun batteryOptimizationIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Shows Shizuku's permission dialog. Only works while Shizuku is running. */
    fun requestShizukuPermission() = Shell.requestPermission()
}
