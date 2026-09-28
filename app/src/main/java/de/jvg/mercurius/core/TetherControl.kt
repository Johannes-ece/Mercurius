package de.jvg.mercurius.core

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Looper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Starts and stops the Wi-Fi hotspot through the system TetheringManager, exactly like the
 * Quick Settings tile does, so it uses the phone's saved hotspot name, password and settings.
 *
 * TetheringManager is a system API, so this only works in a process running as the shell user
 * (Shizuku's user service), which holds TETHER_PRIVILEGED. It is called by reflection because
 * system APIs are not part of the public SDK.
 */
object TetherControl {
    private const val TETHERING_WIFI = 0

    // TetheringManager.TETHER_ERROR_DUPLICATE_REQUEST: Wi-Fi tethering is already running.
    private const val ERROR_DUPLICATE_REQUEST = 18
    private const val SHELL_PACKAGE = "com.android.shell"

    /** Returns "started" on success, otherwise a description of what went wrong. */
    fun start(context: Context): String = try {
        val manager = tetheringManager(context)
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val latch = CountDownLatch(1)
        var result = "no answer from tethering service"
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { result = "started"; latch.countDown(); null }
                "onTetheringFailed" -> {
                    val error = args?.firstOrNull()
                    result = if (error == ERROR_DUPLICATE_REQUEST) "already on" else "failed with error $error"
                    latch.countDown()
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "MercuriusTetheringCallback"
                else -> null
            }
        }
        val direct = Executor { it.run() }
        val byType = manager.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
        }
        if (byType != null) {
            byType.invoke(manager, TETHERING_WIFI, direct, callback)
        } else {
            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            val request = builder.javaClass.getMethod("build").invoke(builder)
            manager.javaClass.getMethod("startTethering", requestClass, Executor::class.java, callbackClass)
                .invoke(manager, request, direct, callback)
        }
        latch.await(20, TimeUnit.SECONDS)
        result
    } catch (e: Throwable) {
        "error: ${e.cause ?: e}"
    }

    fun stop(context: Context): String = try {
        val manager = tetheringManager(context)
        manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, TETHERING_WIFI)
        "stopped"
    } catch (e: Throwable) {
        "error: ${e.cause ?: e}"
    }

    // The tethering service checks that the calling package belongs to the calling uid (shell),
    // so the manager is built around a context that reports the shell's package as the caller.
    private fun tetheringManager(context: Context): Any {
        val shellContext = ShellPackageContext(context)
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = Supplier { serviceManager.getMethod("getService", String::class.java).invoke(null, "tethering") as IBinder }
        return Class.forName("android.net.TetheringManager")
            .getDeclaredConstructor(Context::class.java, Supplier::class.java)
            .apply { isAccessible = true }
            .newInstance(shellContext, binder)
    }

    private class ShellPackageContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

    /**
     * Test entry point, run as the shell user from a Mac:
     * adb shell CLASSPATH=<apk> app_process /system/bin de.jvg.mercurius.core.TetherControl start|stop
     */
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        println(if (args.firstOrNull() == "stop") stop(context) else start(context))
        System.exit(0)
    }
}
