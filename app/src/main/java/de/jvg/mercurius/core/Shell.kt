package de.jvg.mercurius.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import de.jvg.mercurius.BuildConfig
import de.jvg.mercurius.IShellService
import rikka.shizuku.Shizuku

/** Shell (adb-level) command execution through Shizuku. Only available while Shizuku runs. */
object Shell {
    private const val PERMISSION_REQUEST = 1

    @Volatile private var service: IShellService? = null
    private var initialized = false
    private lateinit var args: Shizuku.UserServiceArgs

    /** Called on the main thread whenever the shell becomes usable. */
    @Volatile var onReady: (() -> Unit)? = null

    val state: ShizukuState
        get() = when {
            service != null -> ShizukuState.READY
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            !hasPermission() -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.CONNECTING
        }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        Shizuku.addBinderReceivedListenerSticky {
            AppLog.i("Shizuku is running")
            bindIfPermitted()
        }
        Shizuku.addBinderDeadListener {
            service = null
            AppLog.i("Shizuku stopped")
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bindIfPermitted()
            else AppLog.i("Shizuku permission denied")
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !hasPermission()) Shizuku.requestPermission(PERMISSION_REQUEST)
        else bindIfPermitted()
    }

    /** Starts the Wi-Fi hotspot with the phone's saved settings, or null when the shell is unavailable. */
    fun startHotspot(): String? = call { it.startHotspot() }

    fun stopHotspot(): String? = call { it.stopHotspot() }

    /** Runs [cmd] with shell privileges and returns its output, or null when the shell is unavailable. */
    fun exec(cmd: String): String? = call { it.exec(cmd) }

    private fun call(block: (IShellService) -> String): String? {
        val s = service ?: return null
        return try {
            block(s)
        } catch (e: Exception) {
            AppLog.i("Shell call failed: $e")
            null
        }
    }

    private fun hasPermission() = Shizuku.pingBinder() && !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun bindIfPermitted() {
        if (hasPermission() && service == null) Shizuku.bindUserService(args, connection)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            service = IShellService.Stub.asInterface(binder)
            AppLog.i("Shell ready")
            onReady?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }
}

/** Instantiated by Shizuku in a separate process running as the shell user. */
class ShellService(private val context: Context) : IShellService.Stub() {
    override fun exec(cmd: String): String {
        val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return "exit=${process.waitFor()}\n$output"
    }

    override fun startHotspot(): String = TetherControl.start(context)

    override fun stopHotspot(): String = TetherControl.stop(context)

    override fun destroy() {
        System.exit(0)
    }
}
