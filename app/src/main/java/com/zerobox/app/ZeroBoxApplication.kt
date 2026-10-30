package com.zerobox.app

import android.app.Application
import android.content.Context
import com.zerobox.runtime.daemon.ZeroRuntimeSession
import com.zerobox.runtime.delegate.ZeroInstrumentation

/**
 * Host Application class for ZeroBox.
 * Installs ZeroInstrumentation on the current ActivityThread during attachBaseContext
 * so slot processes (:p0 ~ :p3) can transparently instantiate and patch guest APK Activities.
 */
class ZeroBoxApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ZeroInstrumentation.ensureInstalledOnCurrentActivityThread(this)
    }

    override fun onCreate() {
        super.onCreate()
        ZeroInstrumentation.ensureInstalledOnCurrentActivityThread(this)
        val procName = getProcessNameCompat()
        if (procName.contains(":p")) {
            ZeroRuntimeSession.getCurrentOrCreateDefault(this)
        }
    }

    private fun getProcessNameCompat(): String {
        return try {
            getProcessName()
        } catch (_: Throwable) {
            packageName
        }
    }
}
