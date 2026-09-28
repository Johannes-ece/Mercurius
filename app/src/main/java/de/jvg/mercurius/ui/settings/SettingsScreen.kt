package de.jvg.mercurius.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jvg.mercurius.BuildConfig
import de.jvg.mercurius.core.AppLog
import de.jvg.mercurius.core.Commands
import de.jvg.mercurius.core.History
import de.jvg.mercurius.core.Prefs
import de.jvg.mercurius.core.Setup
import de.jvg.mercurius.core.ShizukuState
import de.jvg.mercurius.core.StatusStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
    val settingsChanged = { Commands.settingsChanged(context) }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            SetupCard()
            TopUpCard(prefs, settingsChanged)
            HotspotCard(prefs, settingsChanged)
            ActionsCard(notify)
            LogCard(notify)
            Text(
                "Mercurius ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SetupCard() {
    val context = LocalContext.current
    val status by StatusStore.state.collectAsStateWithLifecycle()

    // Permission and Shizuku state live outside Compose, so re-read them on resume and every few seconds.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            refresh++
        }
    }
    val permissions = remember(refresh) { Setup.hasRuntimePermissions(context) }
    val usageAccess = remember(refresh) { Setup.hasUsageAccess(context) }
    val battery = remember(refresh) { Setup.ignoresBatteryOptimizations(context) }
    val shizuku = remember(refresh) { Setup.shizukuState() }
    val premiumSms = remember(refresh) { Setup.premiumSmsAllowed(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    SectionCard("Setup") {
        SetupRow(
            done = permissions,
            title = "SMS and notifications",
            detail = "Needed to send WEITER and to warn you.",
            action = if (permissions) null else {
                { FilledTonalButton(onClick = { permissionLauncher.launch(Setup.runtimePermissions) }) { Text("Grant") } }
            },
        )
        SetupRow(
            done = premiumSms,
            title = "Short code SMS always allowed",
            detail = if (premiumSms) "WEITER goes out without a confirmation popup." else
                "Set Mercurius to Always allow under Open, then tap Test. Test sends one WEITER (free) and turns green if no popup appeared.",
            action = if (premiumSms) null else {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { context.launch(Setup.premiumSmsIntent()) }) { Text("Open") }
                        FilledTonalButton(onClick = { Commands.sendWeiterNow(context) }) { Text("Test") }
                    }
                }
            },
        )
        SetupRow(
            done = usageAccess,
            title = "Usage access",
            detail = "Lets Mercurius count hotspot traffic.",
            action = if (usageAccess) null else {
                { FilledTonalButton(onClick = { context.launch(Setup.usageAccessIntent()) }) { Text("Open") } }
            },
        )
        SetupRow(
            done = battery,
            title = "No battery optimization",
            detail = "Keeps Android from pausing the service.",
            action = if (battery) null else {
                { FilledTonalButton(onClick = { context.launch(Setup.batteryOptimizationIntent(context)) }) { Text("Allow") } }
            },
        )
        SetupRow(
            done = shizuku == ShizukuState.READY,
            title = "Shizuku",
            detail = when (shizuku) {
                ShizukuState.READY -> "Ready. Mercurius can reconnect and restart the hotspot."
                ShizukuState.NOT_RUNNING -> "Not running. Run scripts/start-shizuku.sh from your Mac after every reboot."
                ShizukuState.NO_PERMISSION -> "Running, but Mercurius is not allowed yet."
                ShizukuState.CONNECTING -> "Connecting to the shell service..."
            },
            action = if (shizuku != ShizukuState.NO_PERMISSION) null else {
                { FilledTonalButton(onClick = { Setup.requestShizukuPermission() }) { Text("Allow") } }
            },
        )
        SetupRow(
            done = status.serviceRunning,
            title = "Service",
            detail = if (status.serviceRunning) "Running in the background." else "Stopped. Nothing is being watched.",
            action = {
                if (status.serviceRunning) {
                    OutlinedButton(onClick = { Commands.stop(context) }) { Text("Stop") }
                } else {
                    FilledTonalButton(onClick = { Commands.start(context) }) { Text("Start") }
                }
            },
        )
    }
}

@Composable
private fun TopUpCard(prefs: Prefs, settingsChanged: () -> Unit) {
    var proactive by remember { mutableStateOf(prefs.proactive) }
    var threshold by remember { mutableFloatStateOf(prefs.thresholdMb.toFloat()) }
    var interval by remember { mutableIntStateOf(prefs.checkIntervalMin) }

    SectionCard("Top-up") {
        SwitchRow(
            title = "Top up before O2 asks",
            detail = "After O2's first \"verbraucht\" SMS of the day, also send WEITER based on measured usage, in case an O2 SMS gets lost. Off again at midnight.",
            checked = proactive,
            onCheckedChange = {
                proactive = it
                prefs.proactive = it
                settingsChanged()
            },
        )

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send WEITER after", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "${threshold.roundToInt()} MB",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = threshold,
                onValueChange = { threshold = (it / 100f).roundToInt() * 100f },
                valueRange = 500f..4000f,
                steps = 34,
                enabled = proactive,
                onValueChangeFinished = {
                    prefs.thresholdMb = threshold.roundToInt()
                    settingsChanged()
                },
            )
            Text(
                "O2 books 2 GB per WEITER, so stay a bit below 2000 MB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Text("Check speed every", style = MaterialTheme.typography.bodyLarge)
            val options = listOf(5, 10, 15, 30)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                options.forEachIndexed { index, minutes ->
                    SegmentedButton(
                        selected = interval == minutes,
                        onClick = {
                            interval = minutes
                            prefs.checkIntervalMin = minutes
                            settingsChanged()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        label = { Text("$minutes min") },
                    )
                }
            }
        }
    }
}

@Composable
private fun HotspotCard(prefs: Prefs, settingsChanged: () -> Unit) {
    var keepHotspot by remember { mutableStateOf(prefs.keepHotspot) }

    SectionCard("Hotspot") {
        SwitchRow(
            title = "Keep hotspot on",
            detail = "Restart it within a minute whenever it turns off. Uses the phone's own hotspot name and password. Needs Shizuku.",
            checked = keepHotspot,
            onCheckedChange = {
                keepHotspot = it
                prefs.keepHotspot = it
                settingsChanged()
            },
        )
    }
}

private data class Action(
    val label: String,
    val icon: ImageVector,
    val done: String,
    /** When set, ask before running because the action disrupts something. */
    val confirm: String? = null,
    val run: (Context) -> Unit,
)

private val actions = listOf(
    Action("Send WEITER now", Icons.AutoMirrored.Outlined.Send, "Sending WEITER") { Commands.sendWeiterNow(it) },
    Action("Speed test", Icons.Outlined.Speed, "Speed test started, result in the log") { Commands.speedTest(it) },
    Action("Check hotspot", Icons.Outlined.WifiTethering, "Checking the hotspot") { Commands.checkHotspot(it) },
    Action(
        "Reconnect mobile data", Icons.Outlined.Sync, "Reconnecting mobile data",
        confirm = "Mobile data goes off for a few seconds. Everyone on the hotspot loses internet briefly.",
    ) { Commands.reconnectData(it) },
    Action(
        "Toggle airplane mode", Icons.Outlined.AirplanemodeActive, "Toggling airplane mode",
        confirm = "The phone goes offline for about 15 seconds and the hotspot restarts. Everyone gets disconnected.",
    ) { Commands.toggleAirplane(it) },
    Action("Reset data counter", Icons.Outlined.RestartAlt, "Counter reset") { Commands.resetCounter(it) },
    Action("Diagnostics to log", Icons.Outlined.BugReport, "Diagnostics written to the log") { Commands.diagnostics(it) },
    Action(
        "Clear history", Icons.Outlined.DeleteOutline, "History cleared",
        confirm = "Deletes all stats history. This can't be undone.",
    ) { History.clear() },
)

@Composable
private fun ActionsCard(notify: (String) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Action?>(null) }

    val run = { action: Action ->
        action.run(context)
        notify(action.done)
    }

    SectionCard("Actions") {
        actions.forEach { action ->
            OutlinedButton(
                onClick = { if (action.confirm != null) pending = action else run(action) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(action.label, modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
        }
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("${action.label}?") },
            text = { Text(action.confirm.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    run(action)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogCard(notify: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(expanded) {
        while (expanded) {
            lines = AppLog.recent()
            delay(2_000)
        }
    }

    SectionCard("Log") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "Newest first" else "What Mercurius did and why",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (expanded) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Mercurius log", lines.joinToString("\n")))
                    notify("Log copied")
                }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy log") }
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse log" else "Expand log",
                )
            }
        }
        if (expanded) {
            HorizontalDivider()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    if (lines.isEmpty()) "Nothing logged yet." else lines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

private fun Context.launch(intent: Intent) {
    runCatching { startActivity(intent) }
}
