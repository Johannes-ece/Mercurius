package de.jvg.mercurius

import android.app.Application
import de.jvg.mercurius.core.AppLog
import de.jvg.mercurius.core.History
import de.jvg.mercurius.core.Shell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Shizuku's shell process loads this APK too; only the main process should set things up.
        if (getProcessName() != packageName) return
        AppLog.init(this)
        History.init(this)
        Shell.init(this)
    }
}
